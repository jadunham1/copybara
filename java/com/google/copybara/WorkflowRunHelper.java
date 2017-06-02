/*
 * Copyright (C) 2017 Google Inc.
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

import static com.google.copybara.util.FileUtil.CopySymlinkStrategy.FAIL_OUTSIDE_SYMLINKS;

import com.google.common.base.Preconditions;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableList;
import com.google.copybara.Destination.DestinationStatus;
import com.google.copybara.Destination.WriterResult;
import com.google.copybara.Origin.Reader;
import com.google.copybara.authoring.Authoring;
import com.google.copybara.profiler.Profiler;
import com.google.copybara.profiler.Profiler.ProfilerTask;
import com.google.copybara.util.DiffUtil;
import com.google.copybara.util.FileUtil;
import com.google.copybara.util.console.Console;
import com.google.copybara.util.console.ProgressPrefixConsole;
import com.google.devtools.build.lib.skylarkinterface.SkylarkModule;
import com.google.devtools.build.lib.syntax.SkylarkList;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Runs a single migration step for a {@link Workflow}, using its configuration.
 */
public class WorkflowRunHelper<O extends Revision, D extends Revision> {

  private final Workflow<O, D> workflow;
  private final Path workdir;
  private final O resolvedRef;
  private final Origin.Reader<O> originReader;
  @Nullable
  private final Destination.Reader<D> destinationReader;
  private final Destination.Writer writer;

  @Nullable
  private Optional<String> groupIdentity = null;

  protected WorkflowRunHelper(Workflow<O, D> workflow, Path workdir, O resolvedRef,
      Reader<O> originReader, @Nullable Destination.Reader<D> destinationReader,
      Destination.Writer destinationWriter) throws ValidationException, RepoException {
    this.workflow = Preconditions.checkNotNull(workflow);
    this.workdir = Preconditions.checkNotNull(workdir);
    this.resolvedRef = Preconditions.checkNotNull(resolvedRef);
    this.originReader = Preconditions.checkNotNull(originReader);
    this.destinationReader = destinationReader;
    this.writer = Preconditions.checkNotNull(destinationWriter);
  }

  /**
   * @param workdir working directory to use for the transformations
   * @param resolvedRef revision to migrate
   * @param dryRun if the destination writer should be create in dry-run mode.
   */
  public static <O extends Revision, D extends Revision> WorkflowRunHelper<O, D>
  createWorkflowRunHelper(Workflow<O, D> workflow, Path workdir, O resolvedRef, boolean dryRun)
      throws ValidationException, RepoException {
    return new WorkflowRunHelper<>(workflow, workdir, resolvedRef, workflow.getOrigin()
        .newReader(workflow.getOriginFiles(), workflow.getAuthoring()),
        workflow.getDestination().newReader(workflow.getDestinationFiles()),
        workflow.getDestination().newWriter(workflow.getDestinationFiles(), dryRun));
  }

  /**
   * Get a run helper for the current changes.
   *
   * <p>The list contains the changes in order: First change is the oldest. Last change is the
   * newest.
   */
  protected WorkflowRunHelper<O, D> forChanges(Iterable<? extends Change<?>> currentChanges)
      throws RepoException, ValidationException, IOException {
    return this;
  }

  protected WorkflowRunHelper<O, D> withDryRun()
      throws RepoException, ValidationException, IOException {
    return new WorkflowRunHelper<>(workflow, workdir, resolvedRef, originReader, destinationReader,
        workflow.getDestination().newWriter(workflow.getDestinationFiles(), /*dryRun=*/true));
  }

  protected Path getWorkdir() {
    return workdir;
  }

  protected O getResolvedRef() {
    return resolvedRef;
  }

  /**
   * Authoring configuration.
   */
  Authoring getAuthoring() {
    return workflow.getAuthoring();
  }

  /**
   * Console to use for printing messages.
   */
  Console getConsole() {
    return workflow.getConsole();
  }

  /**
   * Options that change how workflows behave.
   */
  WorkflowOptions workflowOptions() {
    return workflow.getWorkflowOptions();
  }

  boolean isForce() {
    return workflow.isForce();
  }

  boolean isSquashWithoutHistory() {
    return workflow.getWorkflowOptions().squashSkipHistory;
  }

  Destination getDestination() {
    return workflow.getDestination();
  }

  Origin.Reader<O> getOriginReader() {
    return originReader;
  }

  Destination.Reader<D> getDestinationReader() {
    return destinationReader;
  }

  boolean destinationSupportsPreviousRef() {
    return writer.supportsStatus();
  }

  String getWorkflowIdentity(O reference) {
    return workflow.getMigrationIdentity(reference);
  }

  void maybeValidateRepoInLastRevState(@Nullable Metadata metadata) throws RepoException,
      ValidationException, IOException {
    if (!workflow.isCheckLastRevState() || isForce()) {
      return;
    }

    workflow.getGeneralOptions().ioRepoTask("validate_last_rev", () -> {
      O lastRev = workflow.getGeneralOptions().repoTask("get_last_rev",
          this::maybeGetLastRev);

      if (lastRev == null) {
        // Not the job of this function to check for lastrev status.
        return null;
      }
      Change<O> change = originReader.change(lastRev);
      ComputedChanges changes = new ComputedChanges(ImmutableList.of(change), ImmutableList.of());
      WorkflowRunHelper<O, D> helper = forChanges(changes.getCurrent()).withDryRun();

      try {
        workflow.getGeneralOptions().ioRepoTask("migrate", () ->
            helper.migrate(lastRev,
                new ProgressPrefixConsole("Validating last migration: ", helper.getConsole()),
                metadata == null ? new Metadata(change.getMessage(), change.getAuthor()) : metadata,
                changes,
                /*destinationBaseline=*/null,
                getWorkflowIdentity(lastRev)));
        throw new ValidationException("Migration of last-rev '" + lastRev.asString() + "' didn't"
            + " result in an empty change. This means that the result change of that migration was"
            + " modified ouside of Copybara or that new changes happened later in the destination"
            + " without using Copybara. Use --force if you really want to do the migration.");
      } catch (EmptyChangeException ignored) {
      }
      return null;
    });
  }
  /**
   * Performs a full migration, including checking out files from the origin, deleting excluded
   * files, transforming the code, and writing to the destination. This writes to the destination
   * exactly once.
   *
   * @param rev revision to the version which will be written to the destination
   * @param processConsole console to use to print progress messages
   * @param metadata metadata of the change to be migrated
   * @param changes changes included in this migration
   * @param destinationBaseline it not null, use this baseline in the destination
   * @param workflowIdentity if not null, an identifier that destination can use to reuse an
   * existing destination entity (code review for example).
   * @return The result of this migration
   */
  WriterResult migrate(O rev, Console processConsole,
      Metadata metadata, Changes changes, @Nullable String destinationBaseline,
      @Nullable String workflowIdentity)
      throws IOException, RepoException, ValidationException {
    Path checkoutDir = workdir.resolve("checkout");
    try (ProfilerTask ignored = profiler().start("prepare_workdir")) {
      processConsole.progress("Cleaning working directory");
      FileUtil.deleteAllFilesRecursively(workdir);
      Files.createDirectories(checkoutDir);
    }
    processConsole.progress("Checking out the change");
    try (ProfilerTask ignored = profiler().start("origin.checkout")) {
      originReader.checkout(rev, checkoutDir);
    }

    // Remove excluded origin files.
    PathMatcher originFiles = workflow.getOriginFiles().relativeTo(checkoutDir);
    processConsole.progress("Removing excluded origin files");

    int deleted = FileUtil.deleteFilesRecursively(
        checkoutDir, FileUtil.notPathMatcher(originFiles));
    if (deleted != 0) {
      processConsole.info(
          String.format("Removed %d files from workdir that do not match origin_files", deleted));
    }

    Path originCopy = null;
    if (workflow.getReverseTransformForCheck() != null) {
      try (ProfilerTask ignored = profiler().start("reverse_copy")) {
        workflow.getConsole().progress("Making a copy or the workdir for reverse checking");
        originCopy = Files.createDirectories(workdir.resolve("origin"));
        FileUtil.copyFilesRecursively(checkoutDir, originCopy, FAIL_OUTSIDE_SYMLINKS);
      }
    }

    TransformWork transformWork =
        new TransformWork(
            checkoutDir,
            metadata,
            changes,
            workflow.getConsole(),
            new MigrationInfo(workflow.getOrigin().getLabelName(), getDestinationReader()),
            resolvedRef);
    try (ProfilerTask ignored = profiler().start("transforms")) {
      workflow.getTransformation().transform(transformWork);
    }

    if (workflow.getReverseTransformForCheck() != null) {
      workflow.getConsole().progress("Checking that the transformations can be reverted");
      Path reverse;
      try (ProfilerTask ignored = profiler().start("reverse_copy")) {
        reverse = Files.createDirectories(workdir.resolve("reverse"));
        FileUtil.copyFilesRecursively(checkoutDir, reverse, FAIL_OUTSIDE_SYMLINKS);
      }

      try (ProfilerTask ignored = profiler().start("reverse_transform")) {
        workflow.getReverseTransformForCheck()
            .transform(
                new TransformWork(
                    reverse,
                    new Metadata(transformWork.getMessage(), transformWork.getAuthor()),
                    changes,
                    workflow.getConsole(),
                    new MigrationInfo(/*originLabel=*/ null, (ChangeVisitable) null),
                    resolvedRef));
      }
      String diff = new String(DiffUtil.diff(originCopy, reverse, workflow.isVerbose()),
          StandardCharsets.UTF_8);
      if (!diff.trim().isEmpty()) {
        workflow.getConsole().error("Non reversible transformations:\n"
            + DiffUtil.colorize(workflow.getConsole(), diff));
        throw new ValidationException(String.format("Workflow '%s' is not reversible",
            workflow.getName()));
      }
    }

    workflow.getConsole()
        .progress("Checking that destination_files covers all files in transform result");
    new ValidateDestinationFilesVisitor(workflow.getDestinationFiles(), checkoutDir)
        .verifyFilesToWrite();

    // TODO(malcon): Pass metadata object instead
    TransformResult transformResult =
        new TransformResult(
            checkoutDir, rev, transformWork.getAuthor(), transformWork.getMessage(), resolvedRef);
    if (destinationBaseline != null) {
      transformResult = transformResult.withBaseline(destinationBaseline);
    }
    transformResult = transformResult
        .withAskForConfirmation(workflow.isAskForConfirmation())
        .withIdentity(workflowIdentity, computeGroupIdentity());

    WriterResult result;
    try (ProfilerTask ignored = profiler().start("destination.write")) {
      result = writer.write(transformResult, processConsole);
    }
    Verify.verifyNotNull(result, "Destination returned a null result.");
    return result;
  }


  ImmutableList<Change<O>> changesSinceLastImport() throws RepoException, ValidationException {
    return getChanges(getLastRev(), resolvedRef);
  }

  
  ImmutableList<Change<O>> getChanges(@Nullable O from, O to)
      throws RepoException, ValidationException {
    return originReader.changes(from, to);
  }

  /**
   * Get last imported revision or fail if it cannot be found.
   *
   * @throws RepoException if a last revision couldn't be found
   */
  O getLastRev() throws RepoException, ValidationException {
    O lastRev = maybeGetLastRev();
    if (lastRev == null && !isForce()) {
      throw new CannotResolveRevisionException(String.format(
          "Previous revision label %s could not be found in %s and --last-rev flag"
              + " was not passed", workflow.getOrigin().getLabelName(), workflow.getDestination()));
    }
    return lastRev;
  }

  /**
   * Returns the last revision that was imported from this origin to the destination. Returns
   * {@code null} if it cannot be determined.
   *
   * <p>If {@code --last-rev} is specified, that revision will be used. Otherwise, the previous
   * revision will be resolved in the destination with the origin label.
   */
  @Nullable
  private O maybeGetLastRev() throws RepoException, ValidationException {
    if (workflow.getLastRevisionFlag() != null) {
      try {
        return workflow.getOrigin().resolve(workflow.getLastRevisionFlag());
      } catch (RepoException e) {
        throw new CannotResolveRevisionException(
            "Could not resolve --last-rev flag. Please make sure it exists in the origin: "
                + workflow.getLastRevisionFlag(),
            e);
      }
    }

    DestinationStatus status = writer.getDestinationStatus(workflow.getOrigin().getLabelName(),
                                                           computeGroupIdentity());
    return (status == null) ? null : workflow.getOrigin().resolve(status.getBaseline());
  }

  public Profiler profiler() {
    return workflow.profiler();
  }

  /**
   * Return true if this change can be skipped because it would generate a noop in the
   * destination.
   *
   * <p>First we check if the change contains the files of the change and if they match
   * origin_files. Then we also check for potential changes in the config for configs that
   * are stored in the origin.
   */
  boolean skipChanges(Iterable<? extends Change<?>> currentChanges) {
    if (workflowOptions().iterativeAllChanges) {
      return false;
    }

    Set<String> changesFiles = new HashSet<>();
    for (Change<?> change : currentChanges) {
      // We cannot know the files included in one of the changes. Try to migrate then.
      if (change.getChangeFiles() == null) {
        return false;
      }
      changesFiles.addAll(change.getChangeFiles());
    }
    PathMatcher pathMatcher = workflow.getOriginFiles().relativeTo(Paths.get("/"));
    for (String changesFile : changesFiles) {
      if (pathMatcher.matches(Paths.get("/" + changesFile))) {
        return false;
      }
    }
    // This is an heuristic for cases where the Copybara configuration is stored in the same folder
    // as the origin code but excluded.
    //
    // The config root can be a subfolder of the files as seen by the origin. For example:
    // admin/copy.bara.sky could be present in the origin as root/admin/copy.bara.sky.
    // This might give us some false positives but they would be noop migrations.
    for (String changesFile : changesFiles) {
      for (String configPath : workflow.configPaths()) {
        if (changesFile.endsWith(configPath)) {
          return false;
        }
      }
    }
    workflow.configPaths();
    return true;
  }

  @Nullable
  private String computeGroupIdentity() throws RepoException, ValidationException {
    if (groupIdentity == null) {
      groupIdentity = Optional.ofNullable(
          workflow.computeGroupIdentity(originReader.getGroupIdentity(resolvedRef)));
    }
    return groupIdentity.orElse(null);
  }

  @SkylarkModule(name = "ComputedChanges", doc = "Computed changes implementation",
      documented = false)
  static class ComputedChanges extends Changes {
    static final Changes EMPTY = new ComputedChanges(ImmutableList.of(), ImmutableList.of());

    private final SkylarkList<? extends Change<?>> current;
    private final SkylarkList<? extends Change<?>> migrated;

    ComputedChanges(Iterable<? extends Change<?>> current, Iterable<? extends Change<?>> migrated) {
      this.current = SkylarkList.createImmutable(current);
      this.migrated = SkylarkList.createImmutable(migrated);
    }

    @Override
    public SkylarkList<? extends Change<?>> getCurrent() {
      return current;
    }

    @Override
    public SkylarkList<? extends Change<?>> getMigrated() {
      return migrated;
    }
  }
}
