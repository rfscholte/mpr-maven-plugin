package com.onedash.maven.mpr;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Scm;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.scm.ScmException;
import org.apache.maven.scm.ScmFileSet;
import org.apache.maven.scm.command.changelog.ChangeLogScmRequest;
import org.apache.maven.scm.command.changelog.ChangeLogScmResult;
import org.apache.maven.scm.manager.NoSuchScmProviderException;
import org.apache.maven.scm.manager.ScmManager;
import org.apache.maven.scm.repository.ScmRepository;
import org.apache.maven.scm.repository.ScmRepositoryException;
import org.codehaus.plexus.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @author stephenc
 * @since 11/03/2013 10:35
 */
@Mojo(name = "list-roots", requiresProject = true, aggregator = true, threadSafe = true)
public class ListRootsMojo extends AbstractMojo {

    /**
     * Add a new or overwrite the default implementation per provider.
     * The key is the scm prefix and the value is the role hint of the {@link org.apache.maven.scm.provider
     * .ScmProvider}.
     *
     * @see ScmManager#setScmProviderImplementation(String, String)
     */
    @Parameter
    private Map<String, String> providerImplementations;

    @Component
    private MavenSession session;

    @Component
    private ScmManager scmManager;


    public void execute() throws MojoExecutionException, MojoFailureException {
        if (providerImplementations != null) {
            for (Map.Entry<String, String> providerEntry : providerImplementations.entrySet()) {
                getLog().info("Changing the default '" + providerEntry.getKey() + "' provider implementation to '"
                        + providerEntry.getValue() + "'.");
                scmManager.setScmProviderImplementation(providerEntry.getKey(), providerEntry.getValue());
            }
        }

        getLog().info("Analysing reactor projects and checking for changes...");
        Map<MavenProject, Boolean> releaseRoots = new LinkedHashMap<MavenProject, Boolean>();
        List<MavenProject> projects = session.getSortedProjects();
        for (MavenProject p : projects) {
            Scm scm = p.getOriginalModel().getScm();
            if (scm == null) {
                releaseRoots.put(p, null);
                continue;
            }
            try {
                ScmRepository scmRepository = scmManager.makeScmRepository(p.getScm().getConnection());
                ChangeLogScmRequest request = new ChangeLogScmRequest(scmRepository, new ScmFileSet(p.getBasedir()));
                request.setLimit(1);
                ChangeLogScmResult result = scmManager.changeLog(request);
                releaseRoots.put(p, StringUtils.contains(result.getChangeLog().getChangeSets().get(0).getComment(),
                        "[maven-release-plugin] prepare for next development iteration"));
            } catch (ScmRepositoryException e) {
                throw new MojoExecutionException(e.getMessage(), e);
            } catch (NoSuchScmProviderException e) {
                throw new MojoExecutionException(e.getMessage(), e);
            } catch (ScmException e) {
                throw new MojoExecutionException(e.getMessage(), e);
            }
        }
        getLog().info("Done.");
        getLog().info("");
        getLog().info("Results");
        getLog().info("-------");
        getLog().info("");
        for (Map.Entry<MavenProject, Boolean> entry : releaseRoots.entrySet()) {
            MavenProject project = entry.getKey();
            getLog().info(toKeyString(project));
            if (entry.getValue() == null) {
                getLog().info("  - Not a release root");
                continue;
            }
            getLog().info(
                    entry.getValue() ? "  - Unmodified since last release" : "  * Changes since last release present");
            Map<MavenProject, String> deps = new LinkedHashMap<MavenProject, String>();
            boolean match = false;
            for (MavenProject p : projects) {
                if (p == project) {
                    continue;
                }
                for (Dependency d : p.getModel().getDependencies()) {
                    if (StringUtils.equals(d.getGroupId(), project.getGroupId()) && StringUtils
                            .equals(d.getArtifactId(), project.getArtifactId())) {
                        deps.put(p, d.getVersion());
                        match = match || StringUtils.equals(d.getVersion(), project.getVersion());
                    }
                }
            }
            if (!deps.isEmpty()) {
                getLog().info("  - Downstream dependencies present in reactor");
                for (Map.Entry<MavenProject, String> e : deps.entrySet()) {
                    getLog().info("      " + toKeyString(e.getKey()) + " <- " + e.getValue());
                }
            }
            if (match) {
                getLog().info("  * Downstream explicit dependencies present in reactor");
                for (Map.Entry<MavenProject, String> e : deps.entrySet()) {
                    if (e.getValue().equals(project.getVersion())) {
                        getLog().info("      " + toKeyString(e.getKey()));
                    }
                }
            }
            if (match && !entry.getValue()) {
                getLog().info("  * RECOMMEND RELEASE");
            }
        }
    }

    private String toKeyString(MavenProject p) {
        return p.getGroupId() + ":" + p.getArtifactId() + ":" + p.getVersion();
    }
}
