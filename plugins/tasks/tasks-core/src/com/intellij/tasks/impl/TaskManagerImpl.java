/*
 * Copyright 2000-2012 JetBrains s.r.o.
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
package com.intellij.tasks.impl;

import com.intellij.notification.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.*;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupManager;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.util.Condition;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vcs.changes.*;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowAnchor;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.tasks.*;
import com.intellij.tasks.config.TaskRepositoriesConfigurable;
import com.intellij.tasks.context.WorkingContextManager;
import com.intellij.tasks.timetracking.TasksToolWindowFactory;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.util.ArrayUtil;
import com.intellij.util.EventDispatcher;
import com.intellij.util.Function;
import com.intellij.util.containers.ConcurrentHashSet;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.containers.Convertor;
import com.intellij.util.ui.UIUtil;
import com.intellij.util.xmlb.XmlSerializationException;
import com.intellij.util.xmlb.XmlSerializer;
import com.intellij.util.xmlb.XmlSerializerUtil;
import com.intellij.util.xmlb.annotations.AbstractCollection;
import com.intellij.util.xmlb.annotations.Property;
import com.intellij.util.xmlb.annotations.Tag;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Timer;
import javax.swing.event.HyperlinkEvent;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;


/**
 * @author Dmitry Avdeev
 */
@State(
  name = "TaskManager",
  storages = {
    @Storage(file = StoragePathMacros.WORKSPACE_FILE)
  }
)
public class TaskManagerImpl extends TaskManager implements ProjectComponent, PersistentStateComponent<TaskManagerImpl.Config>,
                                                            ChangeListDecorator {

  private static final Logger LOG = Logger.getInstance("#com.intellij.tasks.impl.TaskManagerImpl");

  private static final DecimalFormat LOCAL_TASK_ID_FORMAT = new DecimalFormat("LOCAL-00000");
  public static final Comparator<Task> TASK_UPDATE_COMPARATOR = new Comparator<Task>() {
    public int compare(Task o1, Task o2) {
      int i = Comparing.compare(o2.getUpdated(), o1.getUpdated());
      return i == 0 ? Comparing.compare(o2.getCreated(), o1.getCreated()) : i;
    }
  };
  private static final Convertor<Task, String> KEY_CONVERTOR = new Convertor<Task, String>() {
    @Override
    public String convert(Task o) {
      return o.getId();
    }
  };
  static final String TASKS_NOTIFICATION_GROUP = "Task Group";
  public static final int TIME_TRACKING_TIME_UNIT = 1000;

  private final Project myProject;

  private final WorkingContextManager myContextManager;

  private final Map<String, Task> myIssueCache = Collections.synchronizedMap(new LinkedHashMap<String, Task>());

  private final Map<String, LocalTask> myTasks = Collections.synchronizedMap(new LinkedHashMap<String, LocalTask>() {
    @Override
    public LocalTask put(String key, LocalTask task) {
      LocalTask result = super.put(key, task);
      if (size() > myConfig.taskHistoryLength) {
        ArrayList<LocalTask> list = new ArrayList<LocalTask>(values());
        Collections.sort(list, TASK_UPDATE_COMPARATOR);
        for (LocalTask oldest : list) {
          if (!oldest.isDefault()) {
            remove(oldest);
            break;
          }
        }
      }
      return result;
    }
  });

  @NotNull
  private LocalTask myActiveTask = createDefaultTask();
  private Timer myCacheRefreshTimer;

  private volatile boolean myUpdating;
  private final Config myConfig = new Config();
  private final ChangeListAdapter myChangeListListener;
  private final ChangeListManager myChangeListManager;

  private final List<TaskRepository> myRepositories = new ArrayList<TaskRepository>();
  private final EventDispatcher<TaskListener> myDispatcher = EventDispatcher.create(TaskListener.class);
  private Set<TaskRepository> myBadRepositories = new ConcurrentHashSet<TaskRepository>();
  private Timer myTimeTrackingTimer;

  public TaskManagerImpl(Project project,
                         WorkingContextManager contextManager,
                         final ChangeListManager changeListManager) {

    myProject = project;
    myContextManager = contextManager;
    myChangeListManager = changeListManager;

    myChangeListListener = new ChangeListAdapter() {
      @Override
      public void changeListRemoved(ChangeList list) {
        disassociateFromTask((LocalChangeList)list);
      }

      @Override
      public void defaultListChanged(ChangeList oldDefaultList, ChangeList newDefaultList) {
        final LocalTask associatedTask = getAssociatedTask((LocalChangeList)newDefaultList);
        if (associatedTask != null && !getActiveTask().equals(associatedTask)) {
          ApplicationManager.getApplication().invokeLater(new Runnable() {
            public void run() {
              activateTask(associatedTask, true, false);
            }
          }, myProject.getDisposed());
        }
      }
    };
  }

  @Override
  public TaskRepository[] getAllRepositories() {
    return myRepositories.toArray(new TaskRepository[myRepositories.size()]);
  }

  public <T extends TaskRepository> void setRepositories(List<T> repositories) {

    Set<TaskRepository> set = new HashSet<TaskRepository>(myRepositories);
    set.removeAll(repositories);
    myBadRepositories.removeAll(set); // remove all changed reps
    myIssueCache.clear();

    myRepositories.clear();
    myRepositories.addAll(repositories);

    reps:
    for (T repository : repositories) {
      if (repository.isShared() && repository.getUrl() != null) {
        List<TaskProjectConfiguration.SharedServer> servers = getProjectConfiguration().servers;
        TaskRepositoryType type = repository.getRepositoryType();
        for (TaskProjectConfiguration.SharedServer server : servers) {
          if (repository.getUrl().equals(server.url) && type.getName().equals(server.type)) {
            continue reps;
          }
        }
        TaskProjectConfiguration.SharedServer server = new TaskProjectConfiguration.SharedServer();
        server.type = type.getName();
        server.url = repository.getUrl();
        servers.add(server);
      }
    }
  }

  @Override
  public void removeTask(LocalTask task) {
    if (task.isDefault()) return;
    if (myActiveTask.equals(task)) {
      activateTask(myTasks.get(LocalTaskImpl.DEFAULT_TASK_ID), true, false);
    }
    myTasks.remove(task.getId());
    myDispatcher.getMulticaster().taskRemoved(task);
    myContextManager.removeContext(task);
  }

  @Override
  public void addTaskListener(TaskListener listener) {
    myDispatcher.addListener(listener);
  }

  @Override
  public void removeTaskListener(TaskListener listener) {
    myDispatcher.removeListener(listener);
  }

  @NotNull
  @Override
  public LocalTask getActiveTask() {
    return myActiveTask;
  }

  @Override
  public LocalTask findTask(String id) {
    return myTasks.get(id);
  }

  @NotNull
  @Override
  public List<Task> getIssues(@Nullable final String query) {
    return getIssues(query, true);
  }

  @Override
  public List<Task> getIssues(@Nullable final String query, final boolean forceRequest) {
    return getIssues(query, 50, 0, forceRequest, true, new EmptyProgressIndicator());
  }

  @Override
  public List<Task> getIssues(@Nullable String query,
                              int max,
                              long since,
                              boolean forceRequest,
                              final boolean withClosed,
                              @NotNull final ProgressIndicator cancelled) {
    List<Task> tasks = getIssuesFromRepositories(query, max, since, forceRequest, cancelled);
    if (tasks == null) return getCachedIssues(withClosed);
    myIssueCache.putAll(ContainerUtil.newMapFromValues(tasks.iterator(), KEY_CONVERTOR));
    return ContainerUtil.filter(tasks, new Condition<Task>() {
      @Override
      public boolean value(final Task task) {
        return withClosed || !task.isClosed();
      }
    });
  }

  @Override
  public List<Task> getCachedIssues() {
    return getCachedIssues(true);
  }

  @Override
  public List<Task> getCachedIssues(final boolean withClosed) {
    return ContainerUtil.filter(myIssueCache.values(), new Condition<Task>() {
      @Override
      public boolean value(final Task task) {
        return withClosed || !task.isClosed();
      }
    });
  }

  @Nullable
  @Override
  public Task updateIssue(@NotNull String id) {
    for (TaskRepository repository : getAllRepositories()) {
      if (repository.extractId(id) == null) {
        continue;
      }
      try {
        Task issue = repository.findTask(id);
        if (issue != null) {
          LocalTask localTask = myTasks.get(id);
          if (localTask != null) {
            localTask.updateFromIssue(issue);
            return localTask;
          }
          return issue;
        }
      }
      catch (Exception e) {
        LOG.info(e);
      }
    }
    return null;
  }

  @Override
  public List<LocalTask> getLocalTasks() {
    return getLocalTasks(true);
  }

  @Override
  public List<LocalTask> getLocalTasks(final boolean withClosed) {
    synchronized (myTasks) {
      return ContainerUtil.filter(myTasks.values(), new Condition<LocalTask>() {
        @Override
        public boolean value(final LocalTask task) {
          return withClosed || !isLocallyClosed(task);
        }
      });
    }
  }

  @Override
  public LocalTask addTask(Task issue) {
    LocalTaskImpl task = issue instanceof LocalTaskImpl ? (LocalTaskImpl)issue : new LocalTaskImpl(issue);
    addTask(task);
    return task;
  }

  @Override
  public LocalTaskImpl createLocalTask(@NotNull String summary) {
    return createTask(LOCAL_TASK_ID_FORMAT.format(myConfig.localTasksCounter++), summary);
  }

  private static LocalTaskImpl createTask(@NotNull String id, @NotNull String summary) {
    LocalTaskImpl task = new LocalTaskImpl(id, summary);
    Date date = new Date();
    task.setCreated(date);
    task.setUpdated(date);
    return task;
  }

  @Override
  public void activateTask(@NotNull final Task origin, boolean clearContext, boolean createChangelist) {
    if (origin.equals(getActiveTask())) return;

    saveActiveTask();

    if (clearContext) {
      myContextManager.clearContext();
    }
    myContextManager.restoreContext(origin);

    final LocalTask task = doActivate(origin, true);

    if (!isVcsEnabled()) return;
    List<ChangeListInfo> changeLists = task.getChangeLists();
    if (!changeLists.isEmpty()) {
      String id = changeLists.get(0).id;
      LocalChangeList changeList = myChangeListManager.getChangeList(id);
      if (changeList != null) {
        myChangeListManager.setDefaultChangeList(changeList);
      }
      return;
    }
    if (createChangelist) {
      String name = getChangelistName(origin);
      String comment = TaskUtil.getChangeListComment(origin);
      createChangeList(task, name, comment);
    }
  }

  private void saveActiveTask() {
    myContextManager.saveContext(myActiveTask);
    myActiveTask.setUpdated(new Date());
  }

  private LocalTask doActivate(Task origin, boolean explicitly) {
    final LocalTaskImpl task = origin instanceof LocalTaskImpl ? (LocalTaskImpl)origin : new LocalTaskImpl(origin);
    if (explicitly) {
      task.setUpdated(new Date());
    }
    myActiveTask.setActive(false);
    task.setActive(true);
    addTask(task);
    if (task.isIssue()) {
      StartupManager.getInstance(myProject).runWhenProjectIsInitialized(new Runnable() {
        public void run() {
          ProgressManager.getInstance().run(new com.intellij.openapi.progress.Task.Backgroundable(myProject, "Updating " + task.getId()) {

            public void run(@NotNull ProgressIndicator indicator) {
              updateIssue(task.getId());
            }
          });
        }
      });
    }
    LocalTask oldActiveTask = myActiveTask;
    boolean isChanged = !task.equals(oldActiveTask);
    myActiveTask = task;
    if (isChanged) {
      myDispatcher.getMulticaster().taskDeactivated(oldActiveTask);
      myDispatcher.getMulticaster().taskActivated(task);
    }
    return task;
  }

  private void addTask(LocalTaskImpl task) {
    myTasks.put(task.getId(), task);
    myDispatcher.getMulticaster().taskAdded(task);
  }

  @Override
  public boolean testConnection(final TaskRepository repository) {

    TestConnectionTask task = new TestConnectionTask("Test connection") {
      public void run(@NotNull ProgressIndicator indicator) {
        indicator.setText("Connecting to " + repository.getUrl() + "...");
        indicator.setFraction(0);
        indicator.setIndeterminate(true);
        try {
          myConnection = repository.createCancellableConnection();
          if (myConnection != null) {
            Future<Exception> future = ApplicationManager.getApplication().executeOnPooledThread(myConnection);
            while (true) {
              try {
                myException = future.get(100, TimeUnit.MILLISECONDS);
                return;
              }
              catch (TimeoutException ignore) {
                try {
                  indicator.checkCanceled();
                }
                catch (ProcessCanceledException e) {
                  myException = e;
                  myConnection.cancel();
                  return;
                }
              }
              catch (Exception e) {
                myException = e;
                return;
              }
            }
          }
          else {
            try {
              repository.testConnection();
            }
            catch (Exception e) {
              LOG.info(e);
              myException = e;
            }
          }
        }
        catch (Exception e) {
          myException = e;
        }
      }
    };
    ProgressManager.getInstance().run(task);
    Exception e = task.myException;
    if (e == null) {
      myBadRepositories.remove(repository);
      Messages.showMessageDialog(myProject, "Connection is successful", "Connection", Messages.getInformationIcon());
    }
    else if (!(e instanceof ProcessCanceledException)) {
      String message = e.getMessage();
      if (e instanceof UnknownHostException) {
        message = "Unknown host: " + message;
      }
      if (message == null) {
        LOG.error(e);
        message = "Unknown error";
      }
      Messages.showErrorDialog(myProject, StringUtil.capitalize(message), "Error");
    }
    return e == null;
  }

  @SuppressWarnings({"unchecked"})
  @NotNull
  public Config getState() {
    myConfig.tasks = ContainerUtil.map(myTasks.values(), new Function<Task, LocalTaskImpl>() {
      public LocalTaskImpl fun(Task task) {
        return new LocalTaskImpl(task);
      }
    });
    myConfig.servers = XmlSerializer.serialize(getAllRepositories());
    return myConfig;
  }

  @SuppressWarnings({"unchecked"})
  public void loadState(Config config) {
    XmlSerializerUtil.copyBean(config, myConfig);
    myTasks.clear();
    for (LocalTaskImpl task : config.tasks) {
      addTask(task);
    }

    myRepositories.clear();
    Element element = config.servers;
    List<TaskRepository> repositories = loadRepositories(element);
    myRepositories.addAll(repositories);
  }

  public static ArrayList<TaskRepository> loadRepositories(Element element) {
    ArrayList<TaskRepository> repositories = new ArrayList<TaskRepository>();
    for (TaskRepositoryType repositoryType : ourRepositoryTypes) {
      for (Object o : element.getChildren()) {
        if (((Element)o).getName().equals(repositoryType.getName())) {
          try {
            @SuppressWarnings({"unchecked"})
            TaskRepository repository = (TaskRepository)XmlSerializer.deserialize((Element)o, repositoryType.getRepositoryClass());
            if (repository != null) {
              repository.setRepositoryType(repositoryType);
              repositories.add(repository);
            }
          }
          catch (XmlSerializationException e) {
            // ignore
          }
        }
      }
    }
    return repositories;
  }

  public void projectOpened() {

    TaskProjectConfiguration projectConfiguration = getProjectConfiguration();

    servers:
    for (TaskProjectConfiguration.SharedServer server : projectConfiguration.servers) {
      if (server.type == null || server.url == null) {
        continue;
      }
      for (TaskRepositoryType<?> repositoryType : ourRepositoryTypes) {
        if (repositoryType.getName().equals(server.type)) {
          for (TaskRepository repository : myRepositories) {
            if (!repositoryType.equals(repository.getRepositoryType())) {
              continue;
            }
            if (server.url.equals(repository.getUrl())) {
              continue servers;
            }
          }
          TaskRepository repository = repositoryType.createRepository();
          repository.setUrl(server.url);
          myRepositories.add(repository);
        }
      }
    }

    myContextManager.pack(200, 50);
  }

  private TaskProjectConfiguration getProjectConfiguration() {
    return ServiceManager.getService(myProject, TaskProjectConfiguration.class);
  }

  public void projectClosed() {
  }

  @NotNull
  public String getComponentName() {
    return "Task Manager";
  }

  public void initComponent() {
    if (!ApplicationManager.getApplication().isUnitTestMode()) {
      myCacheRefreshTimer = UIUtil.createNamedTimer("TaskManager refresh", myConfig.updateInterval * 60 * 1000, new ActionListener() {
        public void actionPerformed(ActionEvent e) {
          if (myConfig.updateEnabled && !myUpdating) {
            updateIssues(null);
          }
        }
      });
      myCacheRefreshTimer.setInitialDelay(0);
      StartupManager.getInstance(myProject).registerStartupActivity(new Runnable() {
        public void run() {
          myCacheRefreshTimer.start();
        }
      });

      myTimeTrackingTimer = UIUtil.createNamedTimer("TaskManager time tracking", TIME_TRACKING_TIME_UNIT, new ActionListener() {
        @Override
        public void actionPerformed(final ActionEvent e) {
          getActiveTask().setTimeSpent(getActiveTask().getTimeSpent() + TIME_TRACKING_TIME_UNIT);
          getState().myTotallyTimeSpent += TIME_TRACKING_TIME_UNIT;
        }
      });
      StartupManager.getInstance(myProject).registerStartupActivity(new Runnable() {
        public void run() {
          myTimeTrackingTimer.start();
        }
      });
    }

    LocalTask defaultTask = myTasks.get(LocalTaskImpl.DEFAULT_TASK_ID);
    if (defaultTask == null) {
      defaultTask = createDefaultTask();
      addTask(defaultTask);
    }
    // make sure the task is associated with default changelist
    LocalChangeList defaultList = myChangeListManager.findChangeList(LocalChangeList.DEFAULT_NAME);
    if (defaultList != null) {
      ChangeListInfo listInfo = new ChangeListInfo(defaultList);
      if (!defaultTask.getChangeLists().contains(listInfo)) {
        defaultTask.addChangelist(listInfo);
      }
    }

    for (LocalTask localTask : getLocalTasks()) {
      for (Iterator<ChangeListInfo> iterator = localTask.getChangeLists().iterator(); iterator.hasNext(); ) {
        final ChangeListInfo changeListInfo = iterator.next();
        if (myChangeListManager.getChangeList(changeListInfo.id) == null) {
          iterator.remove();
        }
      }
    }

    LocalTask activeTask = null;
    final List<LocalTask> tasks = getLocalTasks();
    Collections.sort(tasks, TASK_UPDATE_COMPARATOR);
    for (LocalTask task : tasks) {
      if (activeTask == null) {
        if (task.isActive()) {
          activeTask = task;
        }
      }
      else {
        task.setActive(false);
      }
    }

    if (activeTask != null) {
      myActiveTask = activeTask;
    }
    doActivate(myActiveTask, false);
    myDispatcher.getMulticaster().taskActivated(myActiveTask);

    myChangeListManager.addChangeListListener(myChangeListListener);

    addTaskListener(new TaskListener() {
      @Override
      public void taskDeactivated(final LocalTask task) {
        activateTimeTrackingToolWindow();
      }

      @Override
      public void taskActivated(final LocalTask task) {
        activateTimeTrackingToolWindow();
      }

      @Override
      public void taskAdded(final LocalTask task) {
        activateTimeTrackingToolWindow();
      }

      @Override
      public void taskRemoved(final LocalTask task) {
        activateTimeTrackingToolWindow();
      }
    });
  }

  private void activateTimeTrackingToolWindow() {
    final String TOOL_WINDOW_ID = "Time Tracking";
    ToolWindow toolWindow = ToolWindowManager.getInstance(myProject).getToolWindow(TOOL_WINDOW_ID);
    if (toolWindow != null) return;
    final TasksToolWindowFactory tasksToolWindowFactory = new TasksToolWindowFactory();
    if (!isTimeTrackingToolWindowAvailable()) return;
    toolWindow = ToolWindowManager.getInstance(myProject).registerToolWindow(TOOL_WINDOW_ID, true, ToolWindowAnchor.RIGHT, myProject, true);
    tasksToolWindowFactory.createToolWindowContent(myProject, toolWindow);
    toolWindow.setAvailable(true, null);
    toolWindow.show(null);
    toolWindow.activate(null);
  }

  public boolean isTimeTrackingToolWindowAvailable() {
    final LocalTask activeTask = getActiveTask();
    final boolean isNotUsed = activeTask.isDefault() && Comparing.equal(activeTask.getCreated(), activeTask.getUpdated());
    return !isNotUsed && ApplicationManager.getApplication().isInternal();
  }

  private static LocalTaskImpl createDefaultTask() {
    return new LocalTaskImpl(LocalTaskImpl.DEFAULT_TASK_ID, "Default task");
  }

  public void disposeComponent() {
    if (myCacheRefreshTimer != null) {
      myCacheRefreshTimer.stop();
    }
    if (myTimeTrackingTimer != null) {
      myTimeTrackingTimer.stop();
    }
    myChangeListManager.removeChangeListListener(myChangeListListener);
  }

  public void updateIssues(final @Nullable Runnable onComplete) {
    TaskRepository first = ContainerUtil.find(getAllRepositories(), new Condition<TaskRepository>() {
      public boolean value(TaskRepository repository) {
        return repository.isConfigured();
      }
    });
    if (first == null) {
      myIssueCache.clear();
      if (onComplete != null) {
        onComplete.run();
      }
      return;
    }
    myUpdating = true;
    if (ApplicationManager.getApplication().isUnitTestMode()) {
      doUpdate(onComplete);
    }
    else {
      ApplicationManager.getApplication().executeOnPooledThread(new Runnable() {
        public void run() {
          doUpdate(onComplete);
        }
      });
    }
  }

  private void doUpdate(@Nullable Runnable onComplete) {
    try {
      List<Task> issues = getIssuesFromRepositories(null, myConfig.updateIssuesCount, 0, false, new EmptyProgressIndicator());
      if (issues == null) return;

      synchronized (myIssueCache) {
        myIssueCache.clear();
        for (Task issue : issues) {
          myIssueCache.put(issue.getId(), issue);
        }
      }
      // update local tasks
      synchronized (myTasks) {
        for (Map.Entry<String, LocalTask> entry : myTasks.entrySet()) {
          Task issue = myIssueCache.get(entry.getKey());
          if (issue != null) {
            entry.getValue().updateFromIssue(issue);
          }
        }
      }
    }
    finally {
      if (onComplete != null) {
        onComplete.run();
      }
      myUpdating = false;
    }
  }

  @Nullable
  private List<Task> getIssuesFromRepositories(@Nullable String request,
                                               int max,
                                               long since,
                                               boolean forceRequest,
                                               @NotNull final ProgressIndicator cancelled) {
    List<Task> issues = null;
    for (final TaskRepository repository : getAllRepositories()) {
      if (!repository.isConfigured() || (!forceRequest && myBadRepositories.contains(repository))) {
        continue;
      }
      try {
        final Task[] tasks = repository.getIssues(request, max, since, cancelled);
        myBadRepositories.remove(repository);
        if (issues == null) issues = new ArrayList<Task>(tasks.length);
        ContainerUtil.addAll(issues, tasks);
      }
      catch (ProcessCanceledException ignored) {
        // OK
      }
      catch (Exception e) {
        //noinspection InstanceofCatchParameter
        if (e instanceof SocketTimeoutException) {
          LOG.warn("Socket timeout from " + repository);
        }
        else {
          LOG.warn("Cannot connect to " + repository, e);
        }
        myBadRepositories.add(repository);
        if (forceRequest) {
          notifyAboutConnectionFailure(repository);
        }
      }
    }
    return issues;
  }

  private void notifyAboutConnectionFailure(final TaskRepository repository) {
    Notifications.Bus.register(TASKS_NOTIFICATION_GROUP, NotificationDisplayType.BALLOON);
    Notifications.Bus.notify(new Notification(TASKS_NOTIFICATION_GROUP, "Cannot connect to " + repository.getUrl(),
                                              "<p><a href=\"\">Configure server...</a></p>", NotificationType.WARNING,
                                              new NotificationListener() {
                                                public void hyperlinkUpdate(@NotNull Notification notification,
                                                                            @NotNull HyperlinkEvent event) {
                                                  TaskRepositoriesConfigurable configurable =
                                                    new TaskRepositoriesConfigurable(myProject);
                                                  ShowSettingsUtil.getInstance().editConfigurable(myProject, configurable);
                                                  if (!ArrayUtil.contains(repository, getAllRepositories())) {
                                                    notification.expire();
                                                  }
                                                }
                                              }), myProject);
  }

  @Override
  public boolean isVcsEnabled() {
    return ProjectLevelVcsManager.getInstance(myProject).getAllActiveVcss().length > 0;
  }

  @Override
  public boolean isLocallyClosed(final LocalTask localTask) {
    return isVcsEnabled() && localTask.getChangeLists().isEmpty();
  }

  @Nullable
  @Override
  public LocalTask getAssociatedTask(LocalChangeList list) {
    for (LocalTask task : getLocalTasks()) {
      for (ChangeListInfo changeListInfo : task.getChangeLists()) {
        if (changeListInfo.id.equals(list.getId())) {
          return task;
        }
      }
    }
    return null;
  }

  @Override
  public void trackContext(LocalChangeList changeList) {
    ChangeListInfo changeListInfo = new ChangeListInfo(changeList);
    String changeListName = changeList.getName();
    LocalTaskImpl task = createLocalTask(changeListName);
    task.addChangelist(changeListInfo);
    addTask(task);
    if (changeList.isDefault()) {
      activateTask(task, false, false);
    }
  }

  @Override
  public void disassociateFromTask(LocalChangeList changeList) {
    ChangeListInfo changeListInfo = new ChangeListInfo(changeList);
    for (LocalTask localTask : getLocalTasks()) {
      if (localTask.getChangeLists().contains(changeListInfo)) {
        localTask.removeChangelist(changeListInfo);
      }
    }
  }

  public void decorateChangeList(LocalChangeList changeList, ColoredTreeCellRenderer cellRenderer, boolean selected,
                                 boolean expanded, boolean hasFocus) {
    LocalTask task = getAssociatedTask(changeList);
    if (task != null && task.isIssue()) {
      cellRenderer.setIcon(task.getIcon());
    }
  }

  public void createChangeList(LocalTask task, String name) {
    String comment = TaskUtil.getChangeListComment(task);
    createChangeList(task, name, comment);
  }

  public void createChangeList(LocalTask task, String name, @Nullable String comment) {
    LocalChangeList changeList = myChangeListManager.findChangeList(name);
    if (changeList == null) {
      changeList = myChangeListManager.addChangeList(name, comment);
    }
    else {
      final LocalTask associatedTask = getAssociatedTask(changeList);
      if (associatedTask != null) {
        associatedTask.removeChangelist(new ChangeListInfo(changeList));
      }
      changeList.setComment(comment);
    }
    task.addChangelist(new ChangeListInfo(changeList));
    myChangeListManager.setDefaultChangeList(changeList);
  }

  public String getChangelistName(Task task) {
    if (task.isIssue() && myConfig.changelistNameFormat != null) {
      return TaskUtil.formatTask(task, myConfig.changelistNameFormat);
    }
    return task.getSummary();
  }

  public ChangeListAdapter getChangeListListener() {
    if (ApplicationManager.getApplication().isUnitTestMode()) {
      return myChangeListListener;
    }
    throw new UnsupportedOperationException();
  }

  public static class Config {

    @Property(surroundWithTag = false)
    @AbstractCollection(surroundWithTag = false, elementTag = "task")
    public List<LocalTaskImpl> tasks = new ArrayList<LocalTaskImpl>();

    public int localTasksCounter = 1;

    public int taskHistoryLength = 50;

    public boolean updateEnabled = true;
    public int updateInterval = 20;
    public int updateIssuesCount = 100;

    public boolean clearContext = true;
    public boolean createChangelist = true;
    public boolean saveContextOnCommit = true;
    public boolean trackContextForNewChangelist = false;
    public boolean markAsInProgress = false;
    public String changelistNameFormat = "{id} {summary}";

    public boolean searchClosedTasks = false;

    public long myTotallyTimeSpent = 0;

    @Tag("servers")
    public Element servers = new Element("servers");
  }

  private abstract class TestConnectionTask extends com.intellij.openapi.progress.Task.Modal {

    protected Exception myException;

    @Nullable
    protected TaskRepository.CancellableConnection myConnection;

    public TestConnectionTask(String title) {
      super(TaskManagerImpl.this.myProject, title, true);
    }

    @Override
    public void onCancel() {
      if (myConnection != null) {
        myConnection.cancel();
      }
    }
  }
}
