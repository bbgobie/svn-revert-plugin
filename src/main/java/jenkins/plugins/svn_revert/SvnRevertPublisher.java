package jenkins.plugins.svn_revert;

import hudson.Extension;
import hudson.Launcher;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Notifier;
import hudson.tasks.Publisher;

import java.io.IOException;

import org.kohsuke.stapler.DataBoundConstructor;

public class SvnRevertPublisher extends Notifier {

    private final String revertMessage;
    private SvnReverter reverter = new SvnReverter();
    private Messenger messenger = new Messenger();

    void setReverter(final SvnReverter reverter) {
        this.reverter = reverter;
    }

    void setMessenger(final Messenger messenger) {
        this.messenger = messenger;
    }

    @Override
    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.BUILD;
    }

    public String getRevertMessage() {
        return revertMessage;
    }

    @DataBoundConstructor
    public SvnRevertPublisher(final String revertMessage) {
        this.revertMessage = revertMessage;
    }

    @Override
    public SvnRevertDescriptorImpl getDescriptor() {
        return (SvnRevertDescriptorImpl)super.getDescriptor();
    }

    @Override
    public boolean perform(final AbstractBuild<?, ?> abstractBuild,
            final Launcher launcher,
            final BuildListener buildListener)
                    throws InterruptedException, IOException {
        messenger.setLogger(buildListener.getLogger());

        if (abstractBuild.getResult() != Result.UNSTABLE) {
            messenger.informBuildStatusNotUnstable();
            return true;
        }
        if (previousBuildStatus(abstractBuild) != Result.SUCCESS) {
            messenger.informPreviousBuildStatusNotSuccess();
            return true;
        }

        return reverter.revert();
    }

    private Result previousBuildStatus(final AbstractBuild<?, ?> abstractBuild) {
        return abstractBuild.getPreviousBuiltBuild().getResult();
    }

    @Extension
    public static final class SvnRevertDescriptorImpl extends BuildStepDescriptor<Publisher> {

        @Override
        public boolean isApplicable(final Class<? extends AbstractProject> arg0) {
            return true;
        }

        @Override
        public String getDisplayName() {
            return "Revert commits that breaks the build";
        }

        public String getRevertMessage() {
            return "Revert message 2";
        }

    }


}
