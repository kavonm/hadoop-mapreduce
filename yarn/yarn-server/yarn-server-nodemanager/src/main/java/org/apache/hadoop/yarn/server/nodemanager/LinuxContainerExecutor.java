/**
* Licensed to the Apache Software Foundation (ASF) under one
* or more contributor license agreements.  See the NOTICE file
* distributed with this work for additional information
* regarding copyright ownership.  The ASF licenses this file
* to you under the Apache License, Version 2.0 (the
* "License"); you may not use this file except in compliance
* with the License.  You may obtain a copy of the License at
*
*     http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package org.apache.hadoop.yarn.server.nodemanager;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.util.Shell.ExitCodeException;
import org.apache.hadoop.util.Shell.ShellCommandExecutor;
import org.apache.hadoop.yarn.server.nodemanager.api.LocalizationProtocol;
import org.apache.hadoop.yarn.server.nodemanager.containermanager.container.Container;
import org.apache.hadoop.yarn.server.nodemanager.containermanager.launcher.ContainerLaunch;
import org.apache.hadoop.yarn.server.nodemanager.containermanager.localizer.ApplicationLocalizer;
import org.apache.hadoop.yarn.server.nodemanager.containermanager.localizer.ResourceLocalizationService;

public class LinuxContainerExecutor extends ContainerExecutor {

  private static final Log LOG = LogFactory
      .getLog(LinuxContainerExecutor.class);

  private String containerExecutorExe;
  protected static final String CONTAINER_EXECUTOR_EXEC_KEY =
    NMConfig.NM_PREFIX + "linux-container-executor.path";
  
  @Override
  public void setConf(Configuration conf) {
    super.setConf(conf);
    containerExecutorExe = getContainerExecutorExecutablePath(conf);
  }

  /**
   * List of commands that the setuid script will execute.
   */
  enum Commands {
    INITIALIZE_JOB(0),
    LAUNCH_CONTAINER(1),
    SIGNAL_CONTAINER(2),
    DELETE_AS_USER(3),
    DELETE_LOG_AS_USER(4);

    private int value;
    Commands(int value) {
      this.value = value;
    }
    int getValue() {
      return value;
    }
  }

  /**
   * Result codes returned from the C task-controller.
   * These must match the values in task-controller.h.
   */
  enum ResultCode {
    OK(0),
    INVALID_USER_NAME(2),
    INVALID_TASK_PID(9),
    INVALID_TASKCONTROLLER_PERMISSIONS(22),
    INVALID_CONFIG_FILE(24);

    private final int value;
    ResultCode(int value) {
      this.value = value;
    }
    int getValue() {
      return value;
    }
  }

  protected String getContainerExecutorExecutablePath(Configuration conf) {
    File hadoopBin = new File(System.getenv("YARN_HOME"), "bin");
    String defaultPath =
      new File(hadoopBin, "container-executor").getAbsolutePath();
    return null == conf
      ? defaultPath
      : conf.get(CONTAINER_EXECUTOR_EXEC_KEY, defaultPath);
  }

  @Override
  public void initApplication(Path nmLocal, LocalizationProtocol localization,
      String user, String appId, Path logDir, List<Path> localDirs)
      throws IOException, InterruptedException {
    // TODO need a type
    InetSocketAddress nmAddr = ((ResourceLocalizationService)localization).getAddress();
    Path appFiles = new Path(nmLocal, ApplicationLocalizer.FILECACHE_FILE);
    Path appTokens = new Path(nmLocal, ApplicationLocalizer.APPTOKEN_FILE);
    List<String> command = new ArrayList<String>(
      Arrays.asList(containerExecutorExe, 
                    user, 
                    Integer.toString(Commands.INITIALIZE_JOB.getValue()),
                    appId,
                    appTokens.toUri().getPath().toString(),
                    appFiles.toUri().getPath().toString()));
    File jvm =                                  // use same jvm as parent
      new File(new File(System.getProperty("java.home"), "bin"), "java");
    command.add(jvm.toString());
    command.add("-classpath");
    command.add(System.getProperty("java.class.path"));
    command.add(ApplicationLocalizer.class.getName());
    command.add(user);
    command.add(appId);
    // add the task tracker's reporting address
    command.add(nmAddr.getHostName());
    command.add(Integer.toString(nmAddr.getPort()));
    command.add(logDir.toUri().getPath().toString());
    for (Path p : localDirs) {
      command.add(p.toUri().getPath().toString());
    }
    String[] commandArray = command.toArray(new String[command.size()]);
    ShellCommandExecutor shExec = new ShellCommandExecutor(commandArray);
    // TODO: DEBUG
    LOG.info("initApplication: " + Arrays.toString(commandArray));
    if (LOG.isDebugEnabled()) {
      LOG.debug("initApplication: " + Arrays.toString(commandArray));
    }
    try {
      shExec.execute();
      if (LOG.isDebugEnabled()) {
        logOutput(shExec.getOutput());
      }
    } catch (ExitCodeException e) {
      int exitCode = shExec.getExitCode();
      LOG.warn("Exit code from task is : " + exitCode);
      logOutput(shExec.getOutput());
      throw new IOException("App initialization failed (" + exitCode + ")", e);
    }
  }

  @Override
  public int launchContainer(Container container, Path nmLocal,
      String user, String appId, List<Path> appDirs, String stdout,
      String stderr) throws IOException {
    Path appWorkDir = new Path(appDirs.get(0), container.toString());
    Path launchScript = new Path(nmLocal, ContainerLaunch.CONTAINER_SCRIPT);
    Path appToken = new Path(nmLocal, ApplicationLocalizer.APPTOKEN_FILE);
    List<String> command = new ArrayList<String>(
      Arrays.asList(containerExecutorExe, 
                    user, 
                    Integer.toString(Commands.LAUNCH_CONTAINER.getValue()),
                    appId,
                    container.toString(),
                    appWorkDir.toString(),
                    launchScript.toUri().getPath().toString(),
                    appToken.toUri().getPath().toString()));
    String[] commandArray = command.toArray(new String[command.size()]);
    ShellCommandExecutor shExec = new ShellCommandExecutor(commandArray);
    launchCommandObjs.put(container.getLaunchContext().getContainerId(), shExec);
    // DEBUG
    LOG.info("launchContainer: " + Arrays.toString(commandArray));
    if (LOG.isDebugEnabled()) {
      LOG.debug("launchContainer: " + Arrays.toString(commandArray));
    }
    try {
      shExec.execute();
      if (LOG.isDebugEnabled()) {
        logOutput(shExec.getOutput());
      }
    } catch (ExitCodeException e) {
      int exitCode = shExec.getExitCode();
      LOG.warn("Exit code from task is : " + exitCode);
      // 143 (SIGTERM) and 137 (SIGKILL) exit codes means the task was
      // terminated/killed forcefully. In all other cases, log the
      // task-controller output
      if (exitCode != 143 && exitCode != 137) {
        LOG.warn("Exception thrown while launching task JVM : ", e);
        logOutput(shExec.getOutput());
      }
      return exitCode;
    } finally {
      launchCommandObjs.remove(container.getLaunchContext().getContainerId());
    }
    if (LOG.isDebugEnabled()) {
      LOG.debug("Output from LinuxContainerExecutor's launchTask follows:");
      logOutput(shExec.getOutput());
    }
    return 0;
  }

  @Override
  public boolean signalContainer(String user, String pid, Signal signal)
      throws IOException {

    String[] command =
        new String[] { containerExecutorExe,
                   user,
                   Integer.toString(Commands.SIGNAL_CONTAINER.getValue()),
                   pid,
                   Integer.toString(signal.getValue()) };
    ShellCommandExecutor shExec = new ShellCommandExecutor(command);
    if (LOG.isDebugEnabled()) {
      LOG.debug("signalTask: " + Arrays.toString(command));
    }
    try {
      shExec.execute();
    } catch (ExitCodeException e) {
      int ret_code = shExec.getExitCode();
      if (ret_code == ResultCode.INVALID_TASK_PID.getValue()) {
        return false;
      }
      logOutput(shExec.getOutput());
      throw new IOException("Problem signalling container " + pid + " with " +
                            signal + "; exit = " + ret_code);
    }
    return true;
  }

  @Override
  public void deleteAsUser(String user, Path subDir, Path... baseDirs)
      throws IOException, InterruptedException {

    if (baseDirs == null || baseDirs.length == 0) {
      LOG.info("Deleting absolute path : " + subDir);
      deleteAsUser(user, subDir);
      return;
    }
    for (Path baseDir : baseDirs) {
      Path del = new Path(baseDir, subDir);
      LOG.info("Deleting path : " + del);
      deleteAsUser(user, del);
    }
  }

  private void deleteAsUser(String user, Path dir) {
    List<String> command = new ArrayList<String>(
        Arrays.asList(containerExecutorExe,
                    user,
                    Integer.toString(Commands.DELETE_AS_USER.getValue()),
                    dir.toUri().getPath()));
    String[] commandArray = command.toArray(new String[command.size()]);
    ShellCommandExecutor shExec = new ShellCommandExecutor(commandArray);
    LOG.info(" -- DEBUG -- deleteAsUser: " + Arrays.toString(commandArray));
    if (LOG.isDebugEnabled()) {
      LOG.debug("deleteAsUser: " + Arrays.toString(commandArray));
    }
    try {
      shExec.execute();
      if (LOG.isDebugEnabled()) {
        logOutput(shExec.getOutput());
      }
    } catch (IOException e) {
      int exitCode = shExec.getExitCode();
      LOG.warn("Exit code from task is : " + exitCode);
      if (exitCode != 0) {
        LOG.error("DeleteAsUser for " + dir.toUri().getPath()
            + " returned with non-zero exit code" + exitCode);
        LOG.error("Output from LinuxContainerExecutor's deleteAsUser follows:");
        logOutput(shExec.getOutput());
      }
    }
  }
}