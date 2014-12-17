package com.cloudbees.jenkins.plugins.okidocki;

import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Proc;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Descriptor;
import hudson.model.Run;
import hudson.remoting.VirtualChannel;
import hudson.tasks.BuildWrapper;
import hudson.tasks.BuildWrapperDescriptor;
import hudson.util.ArgumentListBuilder;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Decorate Launcher so that every command executed by a build step is actually ran inside docker container.
 * TODO run docker container during setup, then use docker-enter to attach command to existing container
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
public class DockerBuildWrapper extends BuildWrapper {

    public DockerImageSelector selector;

    @DataBoundConstructor
    public DockerBuildWrapper(DockerImageSelector selector) {
        this.selector = selector;
    }

    @Override
    public Environment setUp(AbstractBuild build, final Launcher launcher, BuildListener listener) throws IOException, InterruptedException {

        return new Environment() {
            @Override
            public boolean tearDown(AbstractBuild build, BuildListener listener) throws IOException, InterruptedException {
                return build.getAction(BuiltInContainer.class).tearDown();
            }
        };
    }


    @Override
    public Launcher decorateLauncher(final AbstractBuild build, final Launcher launcher, final BuildListener listener) throws IOException, InterruptedException, Run.RunnerAbortedException {
        final Docker docker = new Docker(launcher, listener);
        final BuiltInContainer runInContainer = new BuiltInContainer(docker);
        build.addAction(runInContainer);

        return new Launcher.DecoratedLauncher(launcher) {

            @Override
            public Proc launch(ProcStarter starter) throws IOException {

                if (!runInContainer.enabled()) return super.launch(starter);

                // TODO only run the container first time, then ns-enter for next commands to execute.

                if (runInContainer.image == null) {
                    try {
                        runInContainer.image = selector.prepareDockerImage(docker, build, listener);
                    } catch (InterruptedException e) {
                        throw new RuntimeException("Interrupted");
                    }
                }

                if (runInContainer.container == null) {
                    startBuildContainer();
                    listener.getLogger().println("Docker container " + runInContainer.container + " started to host the build");
                }

                // TODO need some way to know the command execution status, see https://github.com/docker/docker/issues/8703
                ArgumentListBuilder cmdBuilder = new ArgumentListBuilder();
                cmdBuilder.add("docker", "exec", "-t", runInContainer.container);

                List<String> originalCmds = starter.cmds();
                boolean[] originalMask = starter.masks();
                for (int i = 0; i < originalCmds.size(); i++) {
                    boolean masked = originalMask == null ? false : i < originalMask.length ? originalMask[i] : false;
                    cmdBuilder.add(originalCmds.get(i), masked);
                }

                starter.cmds(cmdBuilder);
                return super.launch(starter);
            }

            private void startBuildContainer() throws IOException {
                try {
                    String tmp = build.getWorkspace().act(GetTmpdir);
                    EnvVars environment = build.getEnvironment(listener);

                    Map<String, String> volumes = new HashMap<String, String>();
                    Collection<String> volumesFrom;

                    String container = build.getWorkspace().act(GetContainer);
                    String workdir = build.getWorkspace().getRemote();

                    if (container != null) {
                        // Running inside a container: no volumes and volumes-from
                        volumesFrom = Collections.singleton(container);
                    }
                    else {
                        // Running outside of a container: volumes and no volumes-from
                        volumesFrom = Collections.emptyList();
                        // mount workspace in Docker container
                        // use same path in slave and container so `$WORKSPACE` used in scripts will match
                        volumes.put(workdir, workdir);

                        // mount tmpdir so we can access temporary file created to run shell build steps (and few others)
                        volumes.put(tmp,tmp);
                    }

                    runInContainer.container =
                        docker.runDetached(runInContainer.image, workdir, volumes, volumesFrom, environment,
                                "cat"); // Command expected to hung until killed

                } catch (InterruptedException e) {
                    throw new RuntimeException("Interrupted");
                }
            }
        };
    }

    private static FilePath.FileCallable<String> GetTmpdir = new FilePath.FileCallable<String>() {
        public String invoke(File f, VirtualChannel channel) throws IOException, InterruptedException {
            return System.getProperty("java.io.tmpdir");
        }
    };

    private static FilePath.FileCallable<String> GetContainer = new FilePath.FileCallable<String>() {
        public String invoke(File f, VirtualChannel channel) throws IOException, InterruptedException {
            return System.getProperty("oki-docki.running.from.container");
        }
    };

    @Extension
    public static class DescriptorImpl extends BuildWrapperDescriptor {

        @Override
        public String getDisplayName() {
            return "Build inside a Docker container";
        }

        public Collection<Descriptor<DockerImageSelector>> selectors() {
            return Jenkins.getInstance().getDescriptorList(DockerImageSelector.class);
        }

        @Override
        public boolean isApplicable(AbstractProject<?, ?> item) {
            return true;
        }
    }
}
