/*
 * Copyright (C) 2016 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.copybara;

import static com.google.copybara.GeneralOptions.FORCE;
import static com.google.copybara.WorkflowOptions.CHANGE_REQUEST_FROM_SOT_LIMIT_FLAG;
import static com.google.copybara.WorkflowOptions.CHANGE_REQUEST_PARENT_FLAG;
import static com.google.copybara.exception.ValidationException.checkCondition;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Iterables;
import com.google.common.flogger.FluentLogger;
import com.google.copybara.ChangeVisitable.VisitResult;
import com.google.copybara.DestinationEffect.Type;
import com.google.copybara.Origin.Baseline;
import com.google.copybara.Origin.Reader.ChangesResponse;
import com.google.copybara.Origin.Reader.ChangesResponse.EmptyReason;
import com.google.copybara.doc.annotations.DocField;
import com.google.copybara.exception.CannotResolveRevisionException;
import com.google.copybara.exception.ChangeRejectedException;
import com.google.copybara.exception.EmptyChangeException;
import com.google.copybara.exception.RepoException;
import com.google.copybara.exception.ValidationException;
import com.google.copybara.profiler.Profiler.ProfilerTask;
import com.google.copybara.util.console.ProgressPrefixConsole;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 * Workflow type to run between origin an destination
 */
public enum WorkflowMode {
  /**
   * Create a single commit in the destination with new tree state.
   */
  @DocField(description = "Create a single commit in the destination with new tree state.")
  SQUASH {
    @Override
    <O extends Revision, D extends Revision> void run(WorkflowRunHelper<O, D> runHelper)
        throws RepoException, IOException, ValidationException {
      ImmutableList<Change<O>> detectedChanges = ImmutableList.of();
      O current = runHelper.getResolvedRef();
      O lastRev = null;
      if (isHistorySupported(runHelper)) {
        lastRev = maybeGetLastRev(runHelper);
        ChangesResponse<O> response = runHelper.getChanges(lastRev, current);
        if (response.isEmpty()) {
          manageNoChangesDetectedForSquash(runHelper, current, lastRev, response.getEmptyReason());
        } else {
          detectedChanges = response.getChanges();
        }
      }

      Metadata metadata = new Metadata(
          "Project import generated by Copybara.\n",
          // SQUASH workflows always use the default author
          runHelper.getAuthoring().getDefaultAuthor(),
          ImmutableSetMultimap.of());

      runHelper.maybeValidateRepoInLastRevState(metadata);

      WorkflowRunHelper<O, D> helperForChanges = runHelper.forChanges(detectedChanges);
      // Remove changes that don't affect origin_files
      detectedChanges = detectedChanges.stream()
          // Don't replace helperForChanges with runHelper since origin_files could
          // be potentially different in the helper for the current change.
          .filter(change -> !helperForChanges.skipChange(change))
          .collect(ImmutableList.toImmutableList());

      // Try to use the latest change that affected the origin_files roots instead of the
      // current revision, that could be an unrelated change.
      current = detectedChanges.isEmpty()
          ? current
          : Iterables.getLast(detectedChanges).getRevision();

      if (runHelper.isSquashWithoutHistory()) {
        detectedChanges = ImmutableList.of();
      }

      helperForChanges.migrate(
              current,
              lastRev,
              runHelper.getConsole(),
              metadata,
              // Squash notes an Skylark API expect last commit to be the first one.
              new Changes(detectedChanges.reverse(), ImmutableList.of()),
              /*destinationBaseline=*/null,
              runHelper.getResolvedRef());
    }
  },

  /** Import each origin change individually. */
  @DocField(description = "Import each origin change individually.")
  ITERATIVE {
    @Override
    <O extends Revision, D extends Revision> void run(WorkflowRunHelper<O, D> runHelper)
        throws RepoException, IOException, ValidationException {
      O lastRev = runHelper.getLastRev();
      ChangesResponse<O> changesResponse =
          runHelper.getChanges(lastRev, runHelper.getResolvedRef());
      if (changesResponse.isEmpty()) {
        ValidationException.checkCondition(
            !changesResponse.getEmptyReason().equals(EmptyReason.UNRELATED_REVISIONS),
            "last imported revision %s is not ancestor of requested revision %s",
            lastRev, runHelper.getResolvedRef());
        throw new EmptyChangeException(
            "No new changes to import for resolved ref: " + runHelper.getResolvedRef().asString());
      }
      int changeNumber = 1;

      ImmutableList<Change<O>> changes = changesResponse.getChanges();
      Iterator<Change<O>> changesIterator = changes.iterator();
      int limit = changes.size();
      if (runHelper.workflowOptions().iterativeLimitChanges < changes.size()) {
        runHelper.getConsole().info(String.format("Importing first %d change(s) out of %d",
            limit, changes.size()));
        limit = runHelper.workflowOptions().iterativeLimitChanges;
      }

      runHelper.maybeValidateRepoInLastRevState(/*metadata=*/null);

      Deque<Change<O>> migrated = new ArrayDeque<>();
      int migratedChanges = 0;
      while (changesIterator.hasNext() && migratedChanges < limit) {
        Change<O> change = changesIterator.next();
        String prefix = String.format(
            "Change %d of %d (%s): ",
            changeNumber, Math.min(changes.size(), limit), change.getRevision().asString());
        ImmutableList<DestinationEffect> result;

        boolean errors = false;
        try (ProfilerTask ignored = runHelper.profiler().start(change.getRef())) {
          ImmutableList<Change<O>> current = ImmutableList.of(change);
          WorkflowRunHelper<O, D> currentHelper = runHelper.forChanges(current);
          if (currentHelper.skipChange(change)) {
            continue;
          }
          result = currentHelper.migrate(
                      change.getRevision(),
                      lastRev,
                      new ProgressPrefixConsole(prefix, runHelper.getConsole()),
              new Metadata(change.getMessage(), change.getAuthor(),
                  ImmutableSetMultimap.of()),
                      new Changes(current, migrated),
                      /*destinationBaseline=*/null,
                      // Use the current change since we might want to create different
                      // reviews in the destination. Will not work if we want to group
                      // all the changes in the same Github PR
                      change.getRevision());
          migratedChanges++;
          for (DestinationEffect effect : result) {
            if (effect.getType() != Type.NOOP) {
              errors |= !effect.getErrors().isEmpty();
            }
          }
        } catch (EmptyChangeException e) {
          runHelper.getConsole().warnFmt("Migration of origin revision '%s' resulted in an empty"
              + " change in the destination: %s", change.getRevision().asString(), e.getMessage());
        } catch (ValidationException | RepoException e) {
          runHelper.getConsole().errorFmt("Migration of origin revision '%s' failed with error: %s",
              change.getRevision().asString(), e.getMessage());
          throw e;
        }
        migrated.addFirst(change);

        if (errors && changesIterator.hasNext()) {
          // Use the regular console to log prompt and final message, it will be easier to spot
          if (!runHelper.getConsole()
              .promptConfirmation("Continue importing next change?")) {
            String message = String.format("Iterative workflow aborted by user after: %s", prefix);
            runHelper.getConsole().warn(message);
            throw new ChangeRejectedException(message);
          }
        }
        changeNumber++;
      }
      if (migratedChanges == 0) {
        throw new EmptyChangeException(
            String.format(
                "Iterative workflow produced no changes in the destination for resolved ref: %s",
                runHelper.getResolvedRef().asString()));
      }
      logger.atInfo().log("Imported %d change(s) out of %d", migratedChanges, changes.size());
    }
  },
  @DocField(description = "Import an origin tree state diffed by a common parent"
      + " in destination. This could be a GH Pull Request, a Gerrit Change, etc.")
  CHANGE_REQUEST {
    @SuppressWarnings("unchecked")
    @Override
    <O extends Revision, D extends Revision> void run(WorkflowRunHelper<O, D> runHelper)
        throws RepoException, IOException, ValidationException {

      checkCondition(runHelper.destinationSupportsPreviousRef(),
          "'%s' is incompatible with destinations that don't support history"
              + " (For example folder.destination)", CHANGE_REQUEST);
      String originLabelName = runHelper.getDestination().getLabelNameWhenOrigin();
      Optional<Baseline<O>> baseline;
      /*originRevision=*/
      baseline = Strings.isNullOrEmpty(runHelper.workflowOptions().changeBaseline)
          ? runHelper.getOriginReader().findBaseline(runHelper.getResolvedRef(), originLabelName)
          : Optional.of(
              new Baseline<O>(runHelper.workflowOptions().changeBaseline, /*originRevision=*/null));

      runChangeRequest(runHelper, baseline);
    }},
    @DocField(
        description = "Import **from** the Source-of-Truth. This mode is useful when, despite the"
            + " pending change being already in the SoT, the users want to review the code on a"
            + " different system."
    )
    CHANGE_REQUEST_FROM_SOT {
    @Override
    <O extends Revision, D extends Revision> void run(WorkflowRunHelper<O, D> runHelper)
        throws RepoException, IOException, ValidationException {

      ImmutableList<O> originBaselines =
          Strings.isNullOrEmpty(runHelper.workflowOptions().changeBaseline)
              ? runHelper
                  .getOriginReader()
                  .findBaselinesWithoutLabel(runHelper.getResolvedRef(),
                      runHelper.workflowOptions().changeRequestFromSotLimit)
              : ImmutableList.of(
                  runHelper.originResolve(runHelper.workflowOptions().changeBaseline));

      O originBaseline = null;
      String destinationBaseline = null;
      for (O current : originBaselines) {
        originBaseline = current;
        String originRevision = revisionWithoutReviewInfo(originBaseline.asString());
        destinationBaseline = getDestinationBaseline(runHelper, originRevision);
        if (destinationBaseline != null) {
          break;
        }
      }

      if (destinationBaseline == null) {
        throw new ValidationException(
            /*retryable=*/ true,
            "Couldn't find a change in the destination with %s label and %s value. Make sure"
                + " to sync the submitted changes from the origin -> destination first or use"
                + " SQUASH mode or use %s",
            runHelper.getOriginLabelName(),
            originBaseline.asString(),
            CHANGE_REQUEST_FROM_SOT_LIMIT_FLAG);
      }
      runChangeRequest(runHelper, Optional.of(new Baseline<>(destinationBaseline, originBaseline)));
    }

      @Nullable
      private <O extends Revision, D extends Revision> String getDestinationBaseline(
          WorkflowRunHelper<O, D> runHelper, String originRevision)
          throws RepoException, ValidationException {

        String result = getDestinationBaselineOneAttempt(runHelper, originRevision);
        if (result != null) {
          return result;
        }

        for (Integer delay : runHelper.workflowOptions().changeRequestFromSotRetry) {
          runHelper.getConsole().warnFmt(
              "Couldn't find a change in the destination with %s label and %s value."
                  + " Retrying in %s seconds...",
              runHelper.getOriginLabelName(), originRevision, delay);
          try {
            TimeUnit.SECONDS.sleep(delay);
          } catch (InterruptedException e) {
            throw new RepoException("Interrupted while waiting for CHANGE_REQUEST_FROM_SOT"
                + " destination baseline to be available", e);
          }
          result = getDestinationBaselineOneAttempt(runHelper, originRevision);
          if (result != null) {
            return result;
          }
        }
        return null;
      }

      @Nullable
      private <O extends Revision, D extends Revision> String getDestinationBaselineOneAttempt(
          WorkflowRunHelper<O, D> runHelper, String originRevision)
          throws RepoException, ValidationException {
        String destinationBaseline[] = new String[] {null};
        runHelper
            .getDestinationWriter()
            .visitChangesWithAnyLabel(
                /*start=*/ null,
                ImmutableList.of(runHelper.getOriginLabelName()),
                (change, matchedLabels) -> {
                  for (String value : matchedLabels.values()) {
                    if (originRevision.equals(WorkflowMode.revisionWithoutReviewInfo(value))) {
                      destinationBaseline[0] = change.getRevision().asString();
                      return VisitResult.TERMINATE;
                    }
                  }
                  return VisitResult.CONTINUE;
                });
        return destinationBaseline[0];
      }
    };

  /**
   * Technically revisions can contain additional metadata in the String. For example:
   * 'aaaabbbbccccddddeeeeffff1111222233334444 PatchSet-1'. This method return the identification
   * part.
   */
  private static String revisionWithoutReviewInfo(String r) {
    return r.replaceFirst(" .*", "");
  }

  private static <O extends Revision, D extends Revision> void runChangeRequest(
        WorkflowRunHelper<O, D> runHelper, Optional<Baseline<O>> baseline)
        throws ValidationException, RepoException, IOException {
      if (!baseline.isPresent()) {
        throw new ValidationException(
            "Cannot find matching parent commit in in the destination. Use '"
                + CHANGE_REQUEST_PARENT_FLAG
                + "' flag to force a parent commit to use as baseline in the destination.");
      }
      logger.atInfo().log("Found baseline %s", baseline.get().getBaseline());

      // If --change_request_parent was used, we don't have information about the origin changes
      // included in the CHANGE_REQUEST so we assume the last change is the only change
      ImmutableList<Change<O>> changes;
      if (baseline.get().getOriginRevision() == null) {
        changes = ImmutableList.of(runHelper.getOriginReader().change(runHelper.getResolvedRef()));
      } else {
        ChangesResponse<O> changesResponse = runHelper.getOriginReader()
            .changes(baseline.get().getOriginRevision(),
                runHelper.getResolvedRef());
        if (changesResponse.isEmpty()) {
          throw new EmptyChangeException(String
              .format("Change '%s' doesn't include any change for origin_files = %s",
                  runHelper.getResolvedRef(), runHelper.getOriginFiles()));
        }
        changes = changesResponse.getChanges();
      }

      runHelper
          .forChanges(changes)
          .migrate(
              runHelper.getResolvedRef(),
              /*lastRev=*/null,
              runHelper.getConsole(),
              // Use latest change as the message/author. If it contains multiple changes the user
              // can always use metadata.squash_notes or similar.
              new Metadata(Iterables.getLast(changes).getMessage(),
                  Iterables.getLast(changes).getAuthor(),
                  ImmutableSetMultimap.of()),
              // Squash notes an Skylark API expect last commit to be the first one.
              new Changes(changes.reverse(), ImmutableList.of()),
              baseline.get(),
              runHelper.getResolvedRef());
    }

  private static <O extends Revision, D extends Revision> void manageNoChangesDetectedForSquash(
      WorkflowRunHelper<O, D> runHelper, O current, O lastRev, EmptyReason emptyReason)
      throws ValidationException {
    switch (emptyReason) {
      case NO_CHANGES:
        String noChangesMsg =
            String.format(
                "No changes%s up to %s match any origin_files",
                lastRev == null ? "" : " from " + lastRev.asString(), current.asString());
        if (!runHelper.isForce()) {
          throw new EmptyChangeException(
              String.format(
                  "%s. Use %s if you really want to run the migration anyway.",
                  noChangesMsg, GeneralOptions.FORCE));
        }
        runHelper
            .getConsole()
            .warnFmt("%s. Migrating anyway because of %s", noChangesMsg, GeneralOptions.FORCE);
        break;
      case TO_IS_ANCESTOR:
        if (!runHelper.isForce()) {
          throw new EmptyChangeException(
              String.format(
                  "'%s' has been already migrated. Use %s if you really want to run the migration"
                      + " again (For example if the copy.bara.sky file has changed).",
                  current.asString(), GeneralOptions.FORCE));
        }
        runHelper
            .getConsole()
            .warnFmt(
                "'%s' has been already migrated. Migrating anyway" + " because of %s",
                lastRev.asString(), GeneralOptions.FORCE);
        break;
      case UNRELATED_REVISIONS:
        checkCondition(
            runHelper.isForce(),
            String.format(
                "Last imported revision '%s' is not an ancestor of the revision currently being"
                    + " migrated ('%s'). Use %s if you really want to migrate the reference.",
                lastRev, current.asString(), GeneralOptions.FORCE));
        runHelper
            .getConsole()
            .warnFmt(
                "Last imported revision '%s' is not an ancestor of the revision currently being"
                    + " migrated ('%s')",
                lastRev, current.asString());
        break;
    }
  }

  private static boolean isHistorySupported(WorkflowRunHelper<?, ?> helper) {
    return helper.destinationSupportsPreviousRef() && helper.getOriginReader().supportsHistory();
  }

  /**
   * Returns the last rev if possible. If --force is not enabled it will fail if not found.
   */
  @Nullable
  private static <O extends Revision, D extends Revision> O maybeGetLastRev(
      WorkflowRunHelper<O, D> runHelper) throws RepoException, ValidationException {
    try {
      return runHelper.getLastRev();
    } catch (CannotResolveRevisionException e) {
      if (runHelper.isForce()) {
        runHelper.getConsole().warnFmt(
            "Cannot find last imported revision, but proceeding because of %s flag",
            GeneralOptions.FORCE);
      } else {
        throw new ValidationException(e,
            "Cannot find last imported revision. Use %s if you really want to proceed with the"
                + " migration", FORCE);
      }
      return null;
    }
  }

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  abstract <O extends Revision, D extends Revision> void run(
      WorkflowRunHelper<O, D> runHelper) throws RepoException, IOException, ValidationException;
}
