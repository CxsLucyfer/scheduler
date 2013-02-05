package com.twitter.mesos.scheduler;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;

import org.easymock.IExpectationSetters;
import org.junit.Before;
import org.junit.Test;

import com.twitter.common.quantity.Amount;
import com.twitter.common.quantity.Time;
import com.twitter.common.testing.EasyMockTest;
import com.twitter.mesos.auth.SessionValidator;
import com.twitter.mesos.auth.SessionValidator.AuthFailedException;
import com.twitter.mesos.gen.AssignedTask;
import com.twitter.mesos.gen.Constraint;
import com.twitter.mesos.gen.CreateJobResponse;
import com.twitter.mesos.gen.DrainHostsResponse;
import com.twitter.mesos.gen.EndMaintenanceResponse;
import com.twitter.mesos.gen.ForceTaskStateResponse;
import com.twitter.mesos.gen.HostStatus;
import com.twitter.mesos.gen.Hosts;
import com.twitter.mesos.gen.Identity;
import com.twitter.mesos.gen.JobConfiguration;
import com.twitter.mesos.gen.KillResponse;
import com.twitter.mesos.gen.LimitConstraint;
import com.twitter.mesos.gen.MaintenanceStatusResponse;
import com.twitter.mesos.gen.Quota;
import com.twitter.mesos.gen.ResponseCode;
import com.twitter.mesos.gen.ScheduleStatus;
import com.twitter.mesos.gen.ScheduledTask;
import com.twitter.mesos.gen.SessionKey;
import com.twitter.mesos.gen.SetQuotaResponse;
import com.twitter.mesos.gen.StartMaintenanceResponse;
import com.twitter.mesos.gen.StartUpdateResponse;
import com.twitter.mesos.gen.TaskConstraint;
import com.twitter.mesos.gen.TaskQuery;
import com.twitter.mesos.gen.TwitterTaskInfo;
import com.twitter.mesos.gen.ValueConstraint;
import com.twitter.mesos.scheduler.configuration.ConfigurationManager;
import com.twitter.mesos.scheduler.quota.QuotaManager;
import com.twitter.mesos.scheduler.storage.backup.Recovery;
import com.twitter.mesos.scheduler.storage.backup.StorageBackup;
import com.twitter.mesos.scheduler.storage.testing.StorageTestUtil;

import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import static com.twitter.mesos.gen.MaintenanceMode.DRAINING;
import static com.twitter.mesos.gen.MaintenanceMode.NONE;
import static com.twitter.mesos.gen.MaintenanceMode.SCHEDULED;
import static com.twitter.mesos.gen.ResponseCode.INVALID_REQUEST;
import static com.twitter.mesos.gen.ResponseCode.OK;
import static com.twitter.mesos.scheduler.SchedulerThriftInterface.transitionMessage;
import static com.twitter.mesos.scheduler.configuration.ConfigurationManager.DEDICATED_ATTRIBUTE;
import static com.twitter.mesos.scheduler.configuration.ConfigurationManager.MAX_TASKS_PER_JOB;

public class SchedulerThriftInterfaceTest extends EasyMockTest {

  private static final String ROLE = "bar_role";
  private static final String USER = "foo_user";
  private static final String JOB_NAME = "job_foo";
  private static final Identity ROLE_IDENTITY = new Identity(ROLE, USER);
  private static final SessionKey SESSION = new SessionKey().setUser(USER);

  private StorageTestUtil storageUtil;
  private SchedulerCore scheduler;
  private SessionValidator sessionValidator;
  private QuotaManager quotaManager;
  private StorageBackup backup;
  private Recovery recovery;
  private MaintenanceController maintenance;
  private SchedulerThriftInterface thrift;

  @Before
  public void setUp() {
    storageUtil = new StorageTestUtil(this);
    storageUtil.expectTransactions();
    scheduler = createMock(SchedulerCore.class);
    sessionValidator = createMock(SessionValidator.class);
    quotaManager = createMock(QuotaManager.class);
    backup = createMock(StorageBackup.class);
    recovery = createMock(Recovery.class);
    maintenance = createMock(MaintenanceController.class);
    thrift = new SchedulerThriftInterface(
        storageUtil.storage,
        scheduler,
        sessionValidator,
        quotaManager,
        backup,
        recovery,
        maintenance,
        Amount.of(1L, Time.MILLISECONDS),
        Amount.of(1L, Time.SECONDS));
  }

  @Test
  public void testCreateJob() throws Exception {
    JobConfiguration job = makeJob();
    expectAuth(ROLE, true);
    scheduler.createJob(ParsedConfiguration.fromUnparsed(job));

    control.replay();

    CreateJobResponse response = thrift.createJob(job, SESSION);
    assertEquals(ResponseCode.OK, response.getResponseCode());
  }

  @Test
  public void testCreateJobBadRequest() throws Exception {
    JobConfiguration job = makeJob();
    expectAuth(ROLE, true);
    scheduler.createJob(ParsedConfiguration.fromUnparsed(job));
    expectLastCall().andThrow(new ScheduleException("Error!"));

    control.replay();

    CreateJobResponse response = thrift.createJob(job, SESSION);
    assertEquals(ResponseCode.INVALID_REQUEST, response.getResponseCode());
  }

  @Test
  public void testCreateJobAuthFailure() throws Exception {
    expectAuth(ROLE, false);

    control.replay();

    CreateJobResponse response = thrift.createJob(makeJob(), SESSION);
    assertEquals(ResponseCode.AUTH_FAILED, response.getResponseCode());
  }

  @Test
  public void testKillTasksImmediate() throws Exception {
    TaskQuery query = new TaskQuery()
        .setOwner(ROLE_IDENTITY)
        .setJobName("foo_job");
    ScheduledTask task = new ScheduledTask()
        .setAssignedTask(new AssignedTask()
            .setTask(new TwitterTaskInfo()
                .setOwner(ROLE_IDENTITY)));

    expectAdminAuth(false);
    expectAuth(ROLE, true);
    storageUtil.expectTaskFetch(query, task);
    scheduler.killTasks(query, USER);
    storageUtil.expectTaskFetch(query);
    control.replay();

    KillResponse response = thrift.killTasks(query, SESSION);
    assertEquals(ResponseCode.OK, response.getResponseCode());
  }

  @Test
  public void testKillTasksDelayed() throws Exception {
    TaskQuery query = new TaskQuery()
        .setOwner(ROLE_IDENTITY)
        .setJobName("foo_job");
    ScheduledTask task = new ScheduledTask()
        .setAssignedTask(new AssignedTask()
            .setTask(new TwitterTaskInfo()
                .setOwner(ROLE_IDENTITY)));

    expectAdminAuth(false);
    expectAuth(ROLE, true);
    storageUtil.expectTaskFetch(query, task);
    scheduler.killTasks(query, USER);
    storageUtil.expectTaskFetch(query, task).times(2);
    storageUtil.expectTaskFetch(query);
    control.replay();

    KillResponse response = thrift.killTasks(query, SESSION);
    assertEquals(ResponseCode.OK, response.getResponseCode());
  }

  @Test
  public void testKillTasksAuthFailure() throws Exception {
    TaskQuery query = new TaskQuery()
        .setOwner(ROLE_IDENTITY)
        .setJobName("foo_job");
    ScheduledTask task = new ScheduledTask()
        .setAssignedTask(new AssignedTask()
            .setTask(new TwitterTaskInfo()
                .setOwner(ROLE_IDENTITY)));

    expectAdminAuth(false);
    expectAuth(ROLE, false);
    storageUtil.expectTaskFetch(query, task);

    control.replay();

    KillResponse response = thrift.killTasks(query, SESSION);
    assertEquals(ResponseCode.AUTH_FAILED, response.getResponseCode());
  }

  @Test
  public void testAdminKillTasks() throws Exception {
    TaskQuery query = new TaskQuery()
        .setOwner(ROLE_IDENTITY)
        .setJobName("foo_job");

    expectAdminAuth(true);
    scheduler.killTasks(query, USER);
    storageUtil.expectTaskFetch(query);
    control.replay();

    KillResponse response = thrift.killTasks(query, SESSION);
    assertEquals(ResponseCode.OK, response.getResponseCode());
  }

  @Test
  public void testKillTasksInvalidJobname() throws Exception {
    TaskQuery query = new TaskQuery()
        .setOwner(ROLE_IDENTITY)
        .setJobName("");
    control.replay();
    KillResponse response = thrift.killTasks(query, SESSION);
    assertEquals(ResponseCode.INVALID_REQUEST, response.getResponseCode());
  }

  @Test
  public void testKillNonExistentTasks() throws Exception {
    TaskQuery query = new TaskQuery()
        .setOwner(ROLE_IDENTITY)
        .setJobName("foo_job");

    expectAdminAuth(true);

    scheduler.killTasks(query, USER);
    expectLastCall().andThrow(new ScheduleException("No jobs matching query"));
    control.replay();

    KillResponse response = thrift.killTasks(query, SESSION);
    assertEquals(ResponseCode.INVALID_REQUEST, response.getResponseCode());
  }

  @Test
  public void testSetQuota() throws Exception {
    Quota quota = new Quota()
        .setNumCpus(10)
        .setDiskMb(100)
        .setRamMb(200);
    expectAdminAuth(true);
    quotaManager.setQuota(ROLE, quota);

    control.replay();

    SetQuotaResponse response = thrift.setQuota(ROLE, quota, SESSION);
    assertEquals(ResponseCode.OK, response.getResponseCode());
  }

  @Test
  public void testSetQuotaAuthFailure() throws Exception {
    Quota quota = new Quota()
        .setNumCpus(10)
        .setDiskMb(100)
        .setRamMb(200);
    expectAdminAuth(false);

    control.replay();

    SetQuotaResponse response = thrift.setQuota(ROLE, quota, SESSION);
    assertEquals(ResponseCode.AUTH_FAILED, response.getResponseCode());
  }

  @Test
  public void testForceTaskState() throws Exception {
    String taskId = "task_id_foo";
    ScheduleStatus status = ScheduleStatus.FAILED;

    scheduler.setTaskStatus(Query.byId(taskId), status, transitionMessage(SESSION.getUser()));
    expectAdminAuth(true);

    control.replay();

    ForceTaskStateResponse response = thrift.forceTaskState(taskId, status, SESSION);
    assertEquals(ResponseCode.OK, response.getResponseCode());
  }

  @Test
  public void testForceTaskStateAuthFailure() throws Exception {
    expectAdminAuth(false);

    control.replay();

    ForceTaskStateResponse response = thrift.forceTaskState("task", ScheduleStatus.FAILED, SESSION);
    assertEquals(ResponseCode.AUTH_FAILED, response.getResponseCode());
  }

  @Test
  public void testStartUpdate() throws Exception {
    JobConfiguration job = makeJob();
    String token = "token";

    expectAuth(ROLE, true);
    expect(scheduler.initiateJobUpdate(ParsedConfiguration.fromUnparsed(job)))
        .andReturn(Optional.of(token));

    control.replay();
    StartUpdateResponse resp = thrift.startUpdate(job, SESSION);
    assertEquals(token, resp.getUpdateToken());
    assertEquals(ResponseCode.OK, resp.getResponseCode());
    assertTrue(resp.isRollingUpdateRequired());
  }

  @Test
  public void testStartCronUpdate() throws Exception {
    JobConfiguration job = makeJob();

    expectAuth(ROLE, true);
    expect(scheduler.initiateJobUpdate(ParsedConfiguration.fromUnparsed(job)))
        .andReturn(Optional.<String>absent());

    control.replay();
    StartUpdateResponse resp = thrift.startUpdate(job, SESSION);
    assertEquals(ResponseCode.OK, resp.getResponseCode());
    assertFalse(resp.isRollingUpdateRequired());
  }

  @Test
  public void testCreateJobNoResources() throws Exception {
    expectAuth(ROLE, true);
    control.replay();

    TwitterTaskInfo task = productionTask();
    task.getConfiguration().remove("num_cpus");
    task.getConfiguration().remove("ram_mb");
    task.getConfiguration().remove("disk_mb");
    assertEquals(INVALID_REQUEST, thrift.createJob(makeJob(task), SESSION).getResponseCode());
  }

  @Test
  public void testCreateJobBadCpu() throws Exception {
    expectAuth(ROLE, true);
    control.replay();

    TwitterTaskInfo task = productionTask();
    task.getConfiguration().put("num_cpus", "0.0");
    assertEquals(INVALID_REQUEST, thrift.createJob(makeJob(task), SESSION).getResponseCode());
  }

  @Test
  public void testCreateJobBadRam() throws Exception {
    expectAuth(ROLE, true);
    control.replay();

    TwitterTaskInfo task = productionTask();
    task.getConfiguration().put("ram_mb", "-123");
    assertEquals(INVALID_REQUEST, thrift.createJob(makeJob(task), SESSION).getResponseCode());
  }

  @Test
  public void testCreateJobBadDisk() throws Exception {
    expectAuth(ROLE, true);
    control.replay();

    TwitterTaskInfo task = productionTask();
    task.getConfiguration().put("disk_mb", "0");
    assertEquals(INVALID_REQUEST, thrift.createJob(makeJob(task), SESSION).getResponseCode());
  }

  @Test
  public void testCreateJobPopulateDefaults() throws Exception {
    TwitterTaskInfo task = new TwitterTaskInfo()
        .setContactEmail("testing@twitter.com")
        .setThermosConfig(new byte[] {1, 2, 3})  // Arbitrary opaque data.
        .setNumCpus(1.0)
        .setRamMb(1024)
        .setDiskMb(1024)
        .setIsDaemon(true)
        .setProduction(true)
        .setOwner(ROLE_IDENTITY)
        .setJobName(JOB_NAME);
    JobConfiguration job = makeJob(task);

    expectAuth(ROLE, true);

    JobConfiguration parsed = job.deepCopy();
    for (TwitterTaskInfo parsedTask : parsed.getTaskConfigs()) {
      parsedTask.setConfigParsed(true)
          .setHealthCheckIntervalSecs(30)
          .setShardId(0)
          .setNumCpus(1.0)
          .setPriority(0)
          .setRamMb(1024)
          .setDiskMb(1024)
          .setIsDaemon(true)
          .setProduction(true)
          .setRequestedPorts(ImmutableSet.<String>of())
          .setTaskLinks(ImmutableMap.<String, String>of())
          .setConstraints(ImmutableSet.of(
              ConfigurationManager.hostLimitConstraint(1),
              ConfigurationManager.rackLimitConstraint(1)))
          .setMaxTaskFailures(1);
    }
    // Task configs are placed in a HashSet after deepCopy, equals() does not play nicely between
    // HashSet and ImmutableSet - dropping an ImmutableSet in place keeps equals() happy.
    parsed.setTaskConfigs(ImmutableSet.copyOf(parsed.getTaskConfigs()));

    scheduler.createJob(new ParsedConfiguration(parsed));

    control.replay();

    assertEquals(ResponseCode.OK, thrift.createJob(job, SESSION).getResponseCode());
  }

  @Test
  public void testCreateJobOldConfigPopulateDefaults() throws Exception {
    TwitterTaskInfo task = new TwitterTaskInfo()
        .setOwner(ROLE_IDENTITY)
        .setJobName(JOB_NAME)
        .setContactEmail("testing@twitter.com")
        .setConfiguration(ImmutableMap.<String, String>builder()
            .put("start_command", "echo")
            .put("num_cpus", "1.0")
            .put("ram_mb", "1024")
            .put("disk_mb", "1024")
            .put("daemon", "true")
            .put("production", "true")
            .build());
    JobConfiguration job = makeJob(task);

    expectAuth(ROLE, true);

    JobConfiguration parsed = job.deepCopy();
    for (TwitterTaskInfo parsedTask : parsed.getTaskConfigs()) {
      parsedTask.setConfigParsed(true)
          .setHealthCheckIntervalSecs(30)
          .setStartCommand("echo")
          .setThermosConfig(new byte[] {})
          .setShardId(0)
          .setNumCpus(1.0)
          .setPriority(0)
          .setRamMb(1024)
          .setDiskMb(1024)
          .setIsDaemon(true)
          .setProduction(true)
          .setRequestedPorts(ImmutableSet.<String>of())
          .setTaskLinks(ImmutableMap.<String, String>of())
          .setConstraints(ImmutableSet.of(
              ConfigurationManager.hostLimitConstraint(1),
              ConfigurationManager.rackLimitConstraint(1),
              ConfigurationManager.LEGACY_EXECUTOR))
          .setMaxTaskFailures(1);
    }
    // Task configs are placed in a HashSet after deepCopy, equals() does not play nicely between
    // HashSet and ImmutableSet - dropping an ImmutableSet in place keeps equals() happy.
    parsed.setTaskConfigs(ImmutableSet.copyOf(parsed.getTaskConfigs()));

    scheduler.createJob(new ParsedConfiguration(parsed));

    control.replay();

    assertEquals(ResponseCode.OK, thrift.createJob(job, SESSION).getResponseCode());
  }

  private Set<TwitterTaskInfo> taskCopies(TwitterTaskInfo task, int copies) {
    ImmutableSet.Builder<TwitterTaskInfo> tasks = ImmutableSet.builder();
    for (int i = 0; i < copies; i++) {
      tasks.add(task.deepCopy().setStartCommand(String.valueOf(i)));
    }
    return tasks.build();
  }

  @Test
  public void testCreateJobExceedsTaskLimit() throws Exception {
    expectAuth(ROLE, true);
    control.replay();

    JobConfiguration job = makeJob(taskCopies(nonProductionTask(), MAX_TASKS_PER_JOB.get() + 1));
    assertEquals(INVALID_REQUEST, thrift.createJob(job, SESSION).getResponseCode());
  }

  @Test
  public void testUpdateJobExceedsTaskLimit() throws Exception {
    expectAuth(ROLE, true);
    expectAuth(ROLE, true);
    control.replay();

    thrift.createJob(makeJob(taskCopies(nonProductionTask(), MAX_TASKS_PER_JOB.get())), SESSION);
    JobConfiguration job = makeJob(taskCopies(nonProductionTask(), MAX_TASKS_PER_JOB.get() + 1));
    assertEquals(INVALID_REQUEST, thrift.startUpdate(job, SESSION).getResponseCode());
  }

  @Test
  public void testCreateEmptyJob() throws Exception {
    expectAuth(ROLE, true);
    control.replay();
    JobConfiguration job = new JobConfiguration()
        .setOwner(ROLE_IDENTITY)
        .setName(JOB_NAME)
        .setTaskConfigs(ImmutableSet.<TwitterTaskInfo>of());
    assertEquals(INVALID_REQUEST, thrift.createJob(job, SESSION).getResponseCode());
  }

  @Test
  public void testLimitConstraintForDedicatedJob() throws Exception {
    expectAuth(ROLE, true);
    control.replay();

    TwitterTaskInfo task = nonProductionTask();
    task.addToConstraints(dedicatedConstraint(1));
    assertEquals(INVALID_REQUEST, thrift.createJob(makeJob(task), SESSION).getResponseCode());
  }

  @Test
  public void testMultipleValueConstraintForDedicatedJob() throws Exception {
    expectAuth(ROLE, true);
    control.replay();

    TwitterTaskInfo task = nonProductionTask();
    task.addToConstraints(dedicatedConstraint(ImmutableSet.of("mesos", "test")));
    assertEquals(INVALID_REQUEST, thrift.createJob(makeJob(task), SESSION).getResponseCode());
  }

  @Test
  public void testUnauthorizedDedicatedJob() throws Exception {
    expectAuth(ROLE, true);
    control.replay();

    TwitterTaskInfo task = nonProductionTask();
    task.addToConstraints(dedicatedConstraint(ImmutableSet.of("mesos")));
    assertEquals(INVALID_REQUEST, thrift.createJob(makeJob(task), SESSION).getResponseCode());
  }

  @Test
  public void testRejectsMixedProductionMode() throws Exception {
    expectAuth(ROLE, true);
    control.replay();

    TwitterTaskInfo nonProduction = nonProductionTask();
    TwitterTaskInfo production = productionTask();
    assertEquals(INVALID_REQUEST,
        thrift.createJob(makeJob(nonProduction, production), SESSION).getResponseCode());
  }

  @Test
  public void testCreateJobMissingShardIds() throws Exception {
    expectAuth(ROLE, true);
    control.replay();

    JobConfiguration job = makeJob(productionTask());
    job.getTaskConfigs().iterator().next().unsetShardId();
    assertEquals(INVALID_REQUEST, thrift.createJob(job, SESSION).getResponseCode());
  }

  @Test
  public void testCreateJobDuplicateShardIds() throws Exception {
    expectAuth(ROLE, true);
    control.replay();

    JobConfiguration job =
        makeJob(productionTask("start_command", "1"), productionTask("start_command", "2"));
    for (TwitterTaskInfo task : job.getTaskConfigs()) {
      task.setShardId(0);
    }
    assertEquals(INVALID_REQUEST, thrift.createJob(job, SESSION).getResponseCode());
  }

  @Test
  public void testCreateJobShardIdHole() throws Exception {
    expectAuth(ROLE, true);
    control.replay();

    JobConfiguration job =
        makeJob(productionTask("start_command", "1"), productionTask("start_command", "2"));
    job.setTaskConfigs(ImmutableSet.of(
        Iterables.get(job.getTaskConfigs(), 0).setShardId(0),
        Iterables.get(job.getTaskConfigs(), 0).setShardId(2)));
    assertEquals(INVALID_REQUEST, thrift.createJob(job, SESSION).getResponseCode());
  }

  @Test
  public void testHostMaintenance() throws Exception {
    expectAdminAuth(true).times(6);
    Set<String> hostnames = ImmutableSet.of("a");
    Set<HostStatus> none = ImmutableSet.of(new HostStatus("a", NONE));
    Set<HostStatus> scheduled = ImmutableSet.of(new HostStatus("a", SCHEDULED));
    Set<HostStatus> draining = ImmutableSet.of(new HostStatus("a", DRAINING));
    Set<HostStatus> drained = ImmutableSet.of(new HostStatus("a", DRAINING));
    expect(maintenance.getStatus(hostnames)).andReturn(none);
    expect(maintenance.startMaintenance(hostnames)).andReturn(scheduled);
    expect(maintenance.drain(hostnames)).andReturn(draining);
    expect(maintenance.getStatus(hostnames)).andReturn(draining);
    expect(maintenance.getStatus(hostnames)).andReturn(drained);
    expect(maintenance.endMaintenance(hostnames)).andReturn(none);

    control.replay();

    Hosts hosts = new Hosts(hostnames);

    assertEquals(
        new MaintenanceStatusResponse().setResponseCode(OK).setStatuses(none),
        thrift.maintenanceStatus(hosts, SESSION));
    assertEquals(
        new StartMaintenanceResponse().setResponseCode(OK).setStatuses(scheduled),
        thrift.startMaintenance(hosts, SESSION));
    assertEquals(
        new DrainHostsResponse().setResponseCode(OK).setStatuses(draining),
        thrift.drainHosts(hosts, SESSION));
    assertEquals(
        new MaintenanceStatusResponse().setResponseCode(OK).setStatuses(draining),
        thrift.maintenanceStatus(hosts, SESSION));
    assertEquals(
        new MaintenanceStatusResponse().setResponseCode(OK).setStatuses(drained),
        thrift.maintenanceStatus(hosts, SESSION));
    assertEquals(
        new EndMaintenanceResponse().setResponseCode(OK).setStatuses(none),
        thrift.endMaintenance(hosts, SESSION));
  }

  private JobConfiguration makeJob() {
    return makeJob(nonProductionTask());
  }

  private JobConfiguration makeJob(TwitterTaskInfo task, TwitterTaskInfo... tasks) {
    return makeJob(ImmutableSet.<TwitterTaskInfo>builder().add(task).add(tasks).build());
  }

  private JobConfiguration makeJob(Set<TwitterTaskInfo> tasks) {
    Iterator<TwitterTaskInfo> iterator = tasks.iterator();
    for (int i = 0; i < tasks.size(); i++) {
      iterator.next().setShardId(i);
    }
    return new JobConfiguration()
        .setName(JOB_NAME)
        .setOwner(ROLE_IDENTITY)
        .setTaskConfigs(tasks);
  }

  private IExpectationSetters<?> expectAuth(String role, boolean allowed)
      throws AuthFailedException {

    sessionValidator.checkAuthenticated(SESSION, role);
    if (!allowed) {
      return expectLastCall().andThrow(new AuthFailedException("Denied!"));
    } else {
      return expectLastCall();
    }
  }

  private IExpectationSetters<?> expectAdminAuth(boolean allowed) throws AuthFailedException {
    return expectAuth(SchedulerThriftInterface.ADMIN_ROLE.get(), allowed);
  }

  private static TwitterTaskInfo defaultTask(boolean production, String... additionalParams) {
    Preconditions.checkArgument((additionalParams.length % 2) == 0,
        "Additional params count must be even.");

    Map<String, String> params = Maps.newHashMap(ImmutableMap.<String, String>builder()
        .put("start_command", "date")
        .put("num_cpus", "1.0")
        .put("ram_mb", "1024")
        .put("disk_mb", "1024")
        .put("hdfs_path", "/fake/path")
        .put("production", Boolean.toString(production))
        .build());

    for (int i = 0; i < additionalParams.length; i += 2) {
      params.put(additionalParams[i], additionalParams[i + 1]);
    }

    return new TwitterTaskInfo()
        .setContactEmail("testing@twitter.com")
        .setConfiguration(params);
  }

  private static TwitterTaskInfo productionTask(String... additionalParams) {
    return defaultTask(true, additionalParams);
  }

  private static TwitterTaskInfo nonProductionTask(String... additionalParams) {
    return defaultTask(false, additionalParams);
  }

  private static Constraint dedicatedConstraint(int value) {
    return new Constraint(DEDICATED_ATTRIBUTE, TaskConstraint.limit(new LimitConstraint(value)));
  }

  private static Constraint dedicatedConstraint(Set<String> values) {
    return new Constraint(DEDICATED_ATTRIBUTE,
        TaskConstraint.value(new ValueConstraint(false, values)));
  }
}
