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

import static com.google.copybara.exception.ValidationException.checkCondition;

import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.copybara.Destination.DestinationStatus;
import com.google.copybara.Destination.Writer;
import com.google.copybara.Info.MigrationReference;
import com.google.copybara.Origin.Reader;
import com.google.copybara.Origin.Reader.ChangesResponse;
import com.google.copybara.authoring.Authoring;
import com.google.copybara.config.ConfigFile;
import com.google.copybara.config.Migration;
import com.google.copybara.exception.CommandLineException;
import com.google.copybara.exception.RepoException;
import com.google.copybara.exception.ValidationException;
import com.google.copybara.feedback.Action;
import com.google.copybara.monitor.EventMonitor;
import com.google.copybara.profiler.Profiler;
import com.google.copybara.profiler.Profiler.ProfilerTask;
import com.google.copybara.templatetoken.Token;
import com.google.copybara.templatetoken.Token.TokenType;
import com.google.copybara.util.Glob;
import com.google.copybara.util.Identity;
import com.google.copybara.util.console.Console;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 * Represents a particular migration operation that can occur for a project. Each project can have
 * multiple workflows. Each workflow has a particular origin and destination.
 * @param <O> Origin revision type.
 * @param <D> Destination revision type.
 */
public class Workflow<O extends Revision, D extends Revision> implements Migration {

  private final Logger logger = Logger.getLogger(this.getClass().getName());

  static final String COPYBARA_CONFIG_PATH_IDENTITY_VAR = "copybara_config_path";
  static final String COPYBARA_WORKFLOW_NAME_IDENTITY_VAR = "copybara_workflow_name";
  static final String COPYBARA_REFERENCE_IDENTITY_VAR = "copybara_reference";
  static final String COPYBARA_REFERENCE_LABEL_VAR = "label:";

  private final String name;
  private final Origin<O> origin;
  private final Destination<D> destination;
  private final Authoring authoring;
  private final Transformation transformation;

  @Nullable
  private final String lastRevisionFlag;
  private final boolean initHistoryFlag;
  private final Console console;
  private final GeneralOptions generalOptions;
  private final Glob originFiles;
  private final Glob destinationFiles;
  private final WorkflowMode mode;
  private final WorkflowOptions workflowOptions;

  @Nullable
  private final Transformation reverseTransformForCheck;
  private final boolean verbose;
  private final boolean askForConfirmation;
  private final boolean force;
  private final ConfigFile mainConfigFile;
  private final Supplier<ImmutableMap<String, ConfigFile>> allConfigFiles;
  private final boolean dryRunMode;
  private final ImmutableList<Action> afterMigrationActions;
  private final ImmutableList<Token> changeIdentity;
  private final boolean setRevId;
  private final boolean smartPrune;
  private final boolean migrateNoopChanges;
  private final boolean checkLastRevState;

  public Workflow(
      String name,
      Origin<O> origin,
      Destination<D> destination,
      Authoring authoring,
      Transformation transformation,
      @Nullable String lastRevisionFlag,
      boolean initHistoryFlag,
      GeneralOptions generalOptions,
      Glob originFiles,
      Glob destinationFiles,
      WorkflowMode mode,
      WorkflowOptions workflowOptions,
      @Nullable Transformation reverseTransformForCheck,
      boolean askForConfirmation,
      ConfigFile mainConfigFile,
      Supplier<ImmutableMap<String, ConfigFile>> allConfigFiles,
      boolean dryRunMode,
      boolean checkLastRevState,
      ImmutableList<Action> afterMigrationActions,
      ImmutableList<Token> changeIdentity,
      boolean setRevId,
      boolean smartPrune,
      boolean migrateNoopChanges) {
    this.name = Preconditions.checkNotNull(name);
    this.origin = Preconditions.checkNotNull(origin);
    this.destination = Preconditions.checkNotNull(destination);
    this.authoring = Preconditions.checkNotNull(authoring);
    this.transformation = Preconditions.checkNotNull(transformation);
    this.lastRevisionFlag = lastRevisionFlag;
    this.initHistoryFlag = initHistoryFlag;
    this.console = Preconditions.checkNotNull(generalOptions.console());
    this.generalOptions = generalOptions;
    this.originFiles = Preconditions.checkNotNull(originFiles);
    this.destinationFiles = Preconditions.checkNotNull(destinationFiles);
    this.mode = Preconditions.checkNotNull(mode);
    this.workflowOptions = Preconditions.checkNotNull(workflowOptions);
    this.reverseTransformForCheck = reverseTransformForCheck;
    this.verbose = generalOptions.isVerbose();
    this.askForConfirmation = askForConfirmation;
    this.force = generalOptions.isForced();
    this.mainConfigFile = Preconditions.checkNotNull(mainConfigFile);
    this.allConfigFiles = allConfigFiles;
    this.checkLastRevState = checkLastRevState;
    this.dryRunMode = dryRunMode;
    this.afterMigrationActions = Preconditions.checkNotNull(afterMigrationActions);
    this.changeIdentity = Preconditions.checkNotNull(changeIdentity);
    this.setRevId = setRevId;
    this.smartPrune = smartPrune;
    this.migrateNoopChanges = migrateNoopChanges;
  }

  @Override
  public String getName() {
    return name;
  }

  /**
   * The repository that represents the source of truth
   */
  public Origin<O> getOrigin() {
    return origin;
  }

  /**
   * The destination repository to copy to.
   */
  public Destination<D> getDestination() {
    return destination;
  }

  /**
   * The author mapping between an origin and a destination
   */
  public Authoring getAuthoring() {
    return authoring;
  }

  /**
   * Transformation to run before writing them to the destination.
   */
  public Transformation getTransformation() {
    return transformation;
  }

  public boolean isAskForConfirmation() {
    return askForConfirmation;
  }

  /**
   * Includes only the fields that are part of the configuration: Console is not part of the config,
   * configName is in the parent, and lastRevisionFlag is a command-line flag.
   */
  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("name", name)
        .add("origin", origin)
        .add("destination", destination)
        .add("authoring", authoring)
        .add("transformation", transformation)
        .add("originFiles", originFiles)
        .add("destinationFiles", destinationFiles)
        .add("mode", mode)
        .add("reverseTransformForCheck", reverseTransformForCheck)
        .add("askForConfirmation", askForConfirmation)
        .add("checkLastRevState", checkLastRevState)
        .add("afterMigrationActions", afterMigrationActions)
        .add("changeIdentity", changeIdentity)
        .add("setRevId", setRevId)
        .toString();
  }

  @Override
  public void run(Path workdir, ImmutableList<String> sourceRefs)
      throws RepoException, IOException, ValidationException {
    if (sourceRefs.size() > 1) {
      throw new CommandLineException(
          String.format(
              "Workflow does not support multiple source_ref arguments yet: %s",
              ImmutableList.copyOf(sourceRefs)));
    }
    @Nullable
    String sourceRef = sourceRefs.size() == 1 ? sourceRefs.get(0) : null;

    validateFlags();
    try (ProfilerTask ignore = profiler().start("run/" + name)) {
      console.progress("Getting last revision: "
          + "Resolving " + ((sourceRef == null) ? "origin reference" : sourceRef));
      O resolvedRef = generalOptions.repoTask("origin.resolve_source_ref",
          () ->origin.resolve(sourceRef));

      logger.log(Level.INFO, String.format(
              "Running Copybara for workflow '%s' and ref '%s': %s",
              name, resolvedRef.asString(),
              this.toString()));
      logger.log(Level.INFO, String.format("Using working directory : %s", workdir));
      WorkflowRunHelper<O, D> helper = newRunHelper(workdir, resolvedRef, sourceRef);
      try (ProfilerTask ignored = profiler().start(mode.toString().toLowerCase())) {
        mode.run(helper);
      }
    }
  }

  /**
   * Validates if flags are compatible with this workflow.
   *
   * @throws ValidationException if flags are invalid for this workflow
   */
  private void validateFlags() throws ValidationException {
    checkCondition(!isInitHistory() || mode != WorkflowMode.CHANGE_REQUEST,
        "%s is not compatible with %s",
            WorkflowOptions.INIT_HISTORY_FLAG, WorkflowMode.CHANGE_REQUEST);
    checkCondition(!isCheckLastRevState() || mode != WorkflowMode.CHANGE_REQUEST,
            "%s is not compatible with %s",
                WorkflowOptions.CHECK_LAST_REV_STATE, WorkflowMode.CHANGE_REQUEST);
  }

  protected WorkflowRunHelper<O, D> newRunHelper(Path workdir, O resolvedRef, String rawSourceRef)
      throws ValidationException, RepoException {

    Reader<O> reader = getOrigin()
        .newReader(getOriginFiles(), getAuthoring());
    WriterContext writerContext = new WriterContext(
            name,
            workflowOptions.workflowIdentityUser,
            dryRunMode,
        resolvedRef);
    Writer<D> writer = getDestination().newWriter(writerContext);
    return new WorkflowRunHelper<>(this, workdir, resolvedRef, reader, writer, rawSourceRef);
  }

  /**
   * Return the config files relative to their roots. For example a config file like 'admin/foo/bar'
   * with a root 'admin' would return 'foo/bar'.
   */
  Set<String> configPaths() {
    return allConfigFiles.get().values().stream()
        .map(ConfigFile::getIdentifier)
        .collect(Collectors.toSet());
  }

  @Override
  public Info<? extends Revision> getInfo() throws RepoException, ValidationException {
    return generalOptions.repoTask(
        "info",
        (Callable<Info<? extends Revision>>)
            () -> {
              O lastResolved =
                  generalOptions.repoTask(
                      "origin.last_resolved", () -> origin.resolve(/* reference= */ null));

              Reader<O> oReader = origin.newReader(originFiles, authoring);
              DestinationStatus destinationStatus =
                  generalOptions.repoTask("destination.previous_ref", () -> getDestinationStatus(lastResolved));

              O lastMigrated =
                  generalOptions.repoTask(
                      "origin.last_migrated",
                      () ->
                          (destinationStatus == null)
                              ? null
                              : origin.resolve(destinationStatus.getBaseline()));

              ImmutableList<Change<O>> allChanges =
                  generalOptions.repoTask(
                      "origin.changes",
                      () -> {
                        ChangesResponse<O> changes = oReader.changes(lastMigrated, lastResolved);
                        return changes.isEmpty()
                            ? ImmutableList.of()
                            : ImmutableList.copyOf(changes.getChanges());
                      });
              WorkflowRunHelper<O, D> helper = newRunHelper(
                  // We shouldn't use this path for info
                  Paths.get("shouldnt_be_used"),
                  lastResolved, /*rawSourceRef=*/null);

              List<Change<O>> affectedChanges = new ArrayList<>();
              for (Change<O> change : allChanges) {
                if (helper.getMigratorForChange(change, /*dryRun=*/true).shouldSkipChange(change)) {
                  continue;
                }
                affectedChanges.add(change);
              }
              MigrationReference<O> migrationRef = MigrationReference.create(
                  String.format("workflow_%s", name),
                  lastMigrated,
                  affectedChanges);
              return Info.create(ImmutableList.of(migrationRef));
            });
  }

  @Nullable
  private DestinationStatus getDestinationStatus(O revision) throws RepoException, ValidationException {
    if (getLastRevisionFlag() != null) {
      return new DestinationStatus(getLastRevisionFlag(), ImmutableList.of());
    }
    // TODO(malcon): Should be dryRun=true but some destinations are still not implemented.
    // Should be K since info doesn't write but only read.
    WriterContext writerContext = new WriterContext(
        name, workflowOptions.workflowIdentityUser, /*dryRun=*/false, revision);
    return destination.newWriter(writerContext)
        .getDestinationStatus(getDestinationFiles(), origin.getLabelName());
  }

  @Override
  public ImmutableSetMultimap<String, String> getOriginDescription() {
    return origin.describe(originFiles);
  }

  @Override
  public ImmutableSetMultimap<String, String> getDestinationDescription() {
    return destination.describe(destinationFiles);
  }

  public Glob getOriginFiles() {
    return originFiles;
  }

  public Glob getDestinationFiles() {
    return destinationFiles;
  }

  public Console getConsole() {
    return console;
  }

  public WorkflowOptions getWorkflowOptions() {
    return workflowOptions;
  }

  public boolean isForce() {
    return force;
  }

  @Nullable
  Transformation getReverseTransformForCheck() {
    return reverseTransformForCheck;
  }

  public boolean isVerbose() {
    return verbose;
  }

  @Nullable
  String getLastRevisionFlag() {
    return lastRevisionFlag;
  }

  boolean isInitHistory() {
    return initHistoryFlag;
  }

  public WorkflowMode getMode() {
    return mode;
  }

  @Override
  public String getModeString() {
    return mode.toString();
  }

  boolean isCheckLastRevState() {
    return checkLastRevState;
  }

  boolean isDryRunMode() {
    return dryRunMode;
  }

  /**
   * Migration identity tries to create a stable identifier for the migration that is stable between
   * Copybara invocations for the same reference. For example it will contain the copy.bara.sky
   * config file location relative to the root, the workflow name or the context reference used in
   * the request.
   *
   * <p>This identifier can be used by destinations to reuse code reviews, etc.
   */
  String getMigrationIdentity(Revision requestedRevision, TransformWork transformWork) {
    boolean contextRefDefined = requestedRevision.contextReference() != null;
    // In iterative mode we want to use the revision, since we could have an export from
    // git.origin(master) -> git.gerrit_destination. In that case we want to create one change
    // per origin commit. We are loosing some destination change reuse on cases like rebase (for
    // example git.github_pr_origin -> gerrit. But we can fix this kind of issues in the future
    // if we want to support it (for example with a custom identity using labels).
    String ctxRef = contextRefDefined  && mode != WorkflowMode.ITERATIVE
        ? requestedRevision.contextReference()
        : requestedRevision.asString();
    if (changeIdentity.isEmpty()) {
      return Identity.computeIdentity(
          "ChangeIdentity",
          ctxRef,
          this.name,
          mainConfigFile.getIdentifier(),
          workflowOptions.workflowIdentityUser);
    }
    StringBuilder sb = new StringBuilder();
    for (Token token : changeIdentity) {
      if (token.getType().equals(TokenType.LITERAL)) {
        sb.append(token.getValue());
      } else if (token.getValue().equals(COPYBARA_CONFIG_PATH_IDENTITY_VAR)) {
        sb.append(mainConfigFile.getIdentifier());
      } else if (token.getValue().equals(COPYBARA_WORKFLOW_NAME_IDENTITY_VAR)) {
        sb.append(this.name);
      } else if (token.getValue().equals(COPYBARA_REFERENCE_IDENTITY_VAR)) {
        sb.append(ctxRef);
      } else if (token.getValue().startsWith(COPYBARA_REFERENCE_LABEL_VAR)) {
        String label = token.getValue().substring(COPYBARA_REFERENCE_LABEL_VAR.length());
        String labelValue = transformWork.getLabel(label);
        if (labelValue == null) {
          console.warn(String.format(
              "Couldn't find label '%s'. Using the default identity algorithm", label));
          return Identity.computeIdentity(
              "ChangeIdentity",
              ctxRef,
              this.name,
              mainConfigFile.getIdentifier(),
              workflowOptions.workflowIdentityUser);
        }
        sb.append(labelValue);
      }
    }
    return Identity.hashIdentity(
        MoreObjects.toStringHelper("custom_identity").add("text", sb.toString()),
        workflowOptions.workflowIdentityUser);
  }

  @Override
  public ConfigFile getMainConfigFile() {
    return mainConfigFile;
  }

  public Profiler profiler() {
    return generalOptions.profiler();
  }

  public EventMonitor eventMonitor() {
    return generalOptions.eventMonitor();
  }

  Supplier<ImmutableMap<String, ConfigFile>> getAllConfigFiles() {
    return allConfigFiles;
  }

  public GeneralOptions getGeneralOptions() {
    return generalOptions;
  }

  ImmutableList<Action> getAfterMigrationActions() {
    return afterMigrationActions;
  }

  ImmutableList<Token> getChangeIdentity() {
    return changeIdentity;
  }

  public boolean isSetRevId() {
    return setRevId;
  }

  boolean isSmartPrune() {
    return smartPrune;
  }

  boolean isMigrateNoopChanges() {
    return migrateNoopChanges;
  }
}
