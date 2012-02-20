package jenkins.plugins.svn_revert;

import static hudson.model.Result.SUCCESS;
import static hudson.model.Result.UNSTABLE;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import hudson.FilePath;
import hudson.model.FreeStyleBuild;
import hudson.model.Result;
import hudson.model.AbstractProject;
import hudson.model.FreeStyleProject;
import hudson.scm.NullSCM;
import hudson.scm.SubversionSCM;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import org.jvnet.hudson.test.HudsonHomeLoader.CopyExisting;
import org.jvnet.hudson.test.HudsonTestCase;
import org.jvnet.hudson.test.MockBuilder;
import org.tmatesoft.svn.core.SVNDepth;
import org.tmatesoft.svn.core.wc.SVNClientManager;
import org.tmatesoft.svn.core.wc.SVNStatus;

@SuppressWarnings({ "rawtypes", "deprecation" })
public class SvnRevertPluginTest extends HudsonTestCase {

    private static final long NO_COMMITS = 1;
    private static final long ONE_COMMIT = 2;
    private static final long TWO_COMMITS = 3;
    private static final long THREE_COMMITS = 4;
    private static final String ONE_REVERTED_REVISION =
            String.format(" %s:%s ", NO_COMMITS, ONE_COMMIT);
    private static final String TWO_REVERTED_REVISIONS =
            String.format(" %s:%s ", NO_COMMITS, TWO_COMMITS);
    private static final String MODIFIED_FILE = "modified_file.txt";
    private static final String MODIFIED_FILE_IN_MODULE_2 = "module2" + File.separator + MODIFIED_FILE;
    private static final String MODIFIED_FILE_IN_MODULE_1 =
            "module1" + File.separator + MODIFIED_FILE;
    private static final int LOG_LIMIT = 1000;
    private FreeStyleProject job;
    private String svnUrl;
    private SubversionSCM scm;
    private SubversionSCM rootScm;
    private FreeStyleBuild currentBuild;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        givenSubversionScmWithOneModule();
    }

    public void testShouldNotRevertWhenNotSubversionSCM() throws Exception {
        givenJobWithNullScm();

        currentBuild = whenPreviousJobSuccessfulAndCurrentUnstable();

        assertThat(logFor(currentBuild), containsString(Messenger.NOT_SUBVERSION_SCM));
        assertBuildStatus(UNSTABLE, currentBuild);
    }

    public void testShouldNotRevertWhenBuildStatusIsSuccess() throws Exception {
        givenJobWithSubversionScm();
        givenChangesInSubversionIn(MODIFIED_FILE);

        currentBuild = scheduleBuild();

        assertThat(logFor(currentBuild), containsString(Messenger.BUILD_STATUS_NOT_UNSTABLE));
        assertBuildStatus(SUCCESS, currentBuild);
        assertNothingRevertedSince(ONE_COMMIT);
    }

    public void testShouldLogAndRevertWhenBuildStatusChangesToUnstable() throws Exception {
        givenJobWithSubversionScm();

        currentBuild = whenPreviousJobSuccessfulAndCurrentUnstable();

        final String log = logFor(currentBuild);

        assertBuildStatus(UNSTABLE, currentBuild);
        assertFileReverted(MODIFIED_FILE);
        assertThat(log, containsString(svnUrl));
        assertThat(log, containsString(ONE_REVERTED_REVISION));
    }

    public void testCanRevertMultipleModulesInSameRepository() throws Exception {
        givenJobWithTwoModulesInSameRepository();
        givenPreviousBuildSuccessful();
        givenChangesInSubversionIn(MODIFIED_FILE_IN_MODULE_1);
        givenNextBuildWillBe(UNSTABLE);

        currentBuild = scheduleBuild();

        final String log = logFor(currentBuild);

        assertBuildStatus(UNSTABLE, currentBuild);
        assertFileReverted(MODIFIED_FILE_IN_MODULE_1);
        assertThat(log, containsString("module1"));
        assertThat(log, containsString("module2"));
        assertThatStringContainsTimes(log, ONE_REVERTED_REVISION, 2);
    }

    public void testCanRevertMultipleRevisions() throws Exception {
        givenJobWithSubversionScm();

        currentBuild = whenPreviousJobSuccesfulAndCurrentUnstableWithTwoChanges();

        final String log = logFor(currentBuild);

        assertBuildStatus(UNSTABLE, currentBuild);
        assertFileReverted(MODIFIED_FILE);
        assertThat(log, containsString(svnUrl));
        assertThat(log, containsString(TWO_REVERTED_REVISIONS));
    }

    public void testWillNotRevertIfFileHasChangedSinceBuildStarted() throws Exception {
        givenJobWithSubversionScm();
        givenPreviousBuildSuccessful();
        givenChangesInSubversionIn(MODIFIED_FILE);
        givenNextBuildWillBe(UNSTABLE);

        currentBuild = whenFileChangedDuringBuilding(MODIFIED_FILE);

        assertBuildStatus(UNSTABLE, currentBuild);
        assertNothingRevertedSince(TWO_COMMITS);
        assertThatStringContainsTimes(logFor(currentBuild), ONE_REVERTED_REVISION, 0);
    }

    public void testShouldNotRevertAnythingWhenLastFileToCommitHasChanged() throws Exception {
        givenJobWithTwoModulesInSameRepository();
        givenPreviousBuildSuccessful();
        givenChangesInSubversionIn(MODIFIED_FILE_IN_MODULE_1);
        givenChangesInSubversionIn(MODIFIED_FILE_IN_MODULE_2);
        givenNextBuildWillBe(UNSTABLE);

        currentBuild = whenFileChangedDuringBuilding(MODIFIED_FILE_IN_MODULE_1);

        assertNothingRevertedSince(THREE_COMMITS);
        assertBuildStatus(UNSTABLE, currentBuild);
        assertThatStringContainsTimes(logFor(currentBuild), TWO_REVERTED_REVISIONS, 0);
    }

    private FreeStyleBuild whenFileChangedDuringBuilding(final String file) throws Exception, InterruptedException,
            ExecutionException {
        final Future<FreeStyleBuild> future = job.scheduleBuild2(1);
        givenChangesInSubversionIn(file);
        return future.get();
    }

    private void givenSubversionScmWithOneModule() throws Exception {
        final File repo = getRepoWithTwoModules();
        final String repoUrl = "file://" + repo.getPath();
        svnUrl = repoUrl + "/module1";
        scm = new SubversionSCM(svnUrl);
        rootScm = new SubversionSCM(repoUrl);
    }

    private void givenChangesInSubversionIn(final String file) throws Exception {
        modifyAndCommit(file);
    }

    private void givenTwoChangesInSubversionIn(final String file) throws Exception {
        modifyAndCommit(file);
        modifyAndCommit(file);
    }

    private void givenJobWithTwoModulesInSameRepository() throws Exception, IOException {
        givenJobWithSubversionScm();
        final File repo = getRepoWithTwoModules();
        svnUrl = "file://" + repo.getPath();
        final String[] svnUrls = new String[]{ svnUrl + "/module1", svnUrl + "/module2" };
        final String[] repoLocations= new String[]{ "module1", "module2" };
        scm = new SubversionSCM(svnUrls, repoLocations, true, null);
        rootScm = new SubversionSCM(svnUrl);
        job.setScm(scm);
    }

    private void givenPreviousBuildSuccessful() throws Exception {
        assertBuildStatusSuccess(scheduleBuild());
    }

    private void givenNextBuildWillBe(final Result result) throws Exception {
        job.getBuildersList().add(new MockBuilder(result));
    }

    private void givenJobWithNullScm() throws Exception {
        job = createFreeStyleProject("no-scm-job");
        job.getPublishersList().add(new JenkinsGlue());
        job.setAssignedLabel(hudson.getSelfLabel());
        job.setScm(new NullSCM());
    }

    private void givenJobWithSubversionScm() throws Exception {
        job = createFreeStyleProject("subversion-scm-job");
        job.getPublishersList().add(new JenkinsGlue());
        job.setAssignedLabel(hudson.getSelfLabel());
        job.setScm(scm);
    }

    private FreeStyleBuild whenPreviousJobSuccessfulAndCurrentUnstable() throws Exception,
            InterruptedException, ExecutionException {
        givenPreviousBuildSuccessful();
        givenChangesInSubversionIn(MODIFIED_FILE);
        givenNextBuildWillBe(UNSTABLE);
        return scheduleBuild();
    }

    private FreeStyleBuild whenPreviousJobSuccesfulAndCurrentUnstableWithTwoChanges()
            throws Exception {
        givenPreviousBuildSuccessful();
        givenTwoChangesInSubversionIn(MODIFIED_FILE);
        givenNextBuildWillBe(UNSTABLE);

        return scheduleBuild();
    }

    private void assertNothingRevertedSince(final long revisionNumber) throws Exception {
        assertEquals(revisionNumber, getCurrentSvnRevision());
    }

    private void assertFileReverted(final String path)
            throws IOException, InterruptedException, ExecutionException, Exception {

        final FreeStyleBuild build = getIndependentSubversionBuild(scm);
        final FilePath file = build.getWorkspace().child(path);
        assertFalse("File '" + path + "' is not reverted (because it exists)", file.exists());
    }

    private void assertThatStringContainsTimes(
            final String log, final String string, final int times) {
        final int actualTimes = log.split(string).length - 1;
        assertThat(actualTimes, is(times));

    }

    /**
     * Repo at revision 1 with structure
     *   module1/
     *           file1
     *   module2/
     *           file2
     */
    private File getRepoWithTwoModules() throws Exception {
        return new CopyExisting(getClass().getResource("repoWithTwoModules.zip")).allocate();
    }

    private String logFor(final FreeStyleBuild build) throws IOException {
        final List<String> logLines = build.getLog(LOG_LIMIT);
        System.out.println("Build log: ");
        printLog(logLines);
        final String log = logLines.toString();
        return log;
    }

    private void printLog(final List<String> logLines) {
        for (final String logLine : logLines) {
            System.out.println("   " + logLine);
        }
    }

    private FreeStyleBuild scheduleBuild() throws Exception {
        return job.scheduleBuild2(0).get();
    }

    private void modifyAndCommit(final String path) throws Exception {
        final FreeStyleBuild build = getIndependentSubversionBuild(scm);
        final SVNClientManager svnm = SubversionSCM.createSvnClientManager((AbstractProject) null);

        final FilePath file = build.getWorkspace().child(path);
        if (!file.exists()) {
            file.touch(System.currentTimeMillis());
            svnm.getWCClient().doAdd(new File(file.getRemote()), false, false, false,
                    SVNDepth.INFINITY, false, false);
        } else {
            file.write("random content", "UTF-8");
        }

        svnm.getCommitClient().doCommit(new File[] { new File(file.getRemote()) }, false,
                "test changes", null, null, false, false, SVNDepth.EMPTY);
    }

    private FreeStyleBuild getIndependentSubversionBuild(final SubversionSCM scm) throws IOException,
            Exception, InterruptedException, ExecutionException {
        final FreeStyleProject forCommit = createFreeStyleProject();
        forCommit.setScm(scm);
        forCommit.setAssignedLabel(hudson.getSelfLabel());
        final FreeStyleBuild build = assertBuildStatusSuccess(forCommit.scheduleBuild2(0).get());
        return build;
    }

    private long getCurrentSvnRevision() throws Exception {
        final SVNClientManager svnm = SubversionSCM.createSvnClientManager((AbstractProject) null);
        final FreeStyleBuild build = getIndependentSubversionBuild(rootScm);
        final File workspace = new File(build.getWorkspace().getRemote());
        final SVNStatus status = svnm.getStatusClient().doStatus(workspace, true);
        return status.getRevision().getNumber();
    }

}
