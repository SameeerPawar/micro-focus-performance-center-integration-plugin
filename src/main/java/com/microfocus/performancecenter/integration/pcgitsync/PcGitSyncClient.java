/*
 * © Copyright 2013 EntIT Software LLC
 *  Certain versions of software and/or documents (“Material”) accessible here may contain branding from
 *  Hewlett-Packard Company (now HP Inc.) and Hewlett Packard Enterprise Company.  As of September 1, 2017,
 *  the Material is now offered by Micro Focus, a separately owned and operated company.  Any reference to the HP
 *  and Hewlett Packard Enterprise/HPE marks is historical in nature, and the HP and Hewlett Packard Enterprise/HPE
 *  marks are the property of their respective owners.
 * __________________________________________________________________
 * MIT License
 *
 * © Copyright 2012-2018 Micro Focus or one of its affiliates.
 *
 * The only warranties for products and services of Micro Focus and its affiliates
 * and licensors (“Micro Focus”) are set forth in the express warranty statements
 * accompanying such products and services. Nothing herein should be construed as
 * constituting an additional warranty. Micro Focus shall not be liable for technical
 * or editorial errors or omissions contained herein.
 * The information contained herein is subject to change without notice.
 * ___________________________________________________________________
 *
 */

package com.microfocus.performancecenter.integration.pcgitsync;

import com.cloudbees.plugins.credentials.common.UsernamePasswordCredentials;
import com.microfocus.adm.performancecenter.plugins.common.pcentities.PcException;
import com.microfocus.adm.performancecenter.plugins.common.pcentities.PcScript;
import com.microfocus.adm.performancecenter.plugins.common.pcentities.PcScripts;
import com.microfocus.adm.performancecenter.plugins.common.pcentities.PcTestPlanFolders;
import com.microfocus.adm.performancecenter.plugins.common.pcentities.pcsubentities.test.Test;
import com.microfocus.adm.performancecenter.plugins.common.pcentities.pcsubentities.test.content.common.Common;
import com.microfocus.adm.performancecenter.plugins.common.rest.PcRestProxy;
import com.microfocus.performancecenter.integration.common.helpers.compressor.Compressor;
import com.microfocus.performancecenter.integration.common.helpers.compressor.ICompressor;
import com.microfocus.performancecenter.integration.common.helpers.constants.PcTestRunConstants;
import com.microfocus.performancecenter.integration.common.helpers.services.WorkspaceScripts;
import com.microfocus.performancecenter.integration.common.helpers.services.WorkspaceTests;
import com.microfocus.performancecenter.integration.common.helpers.utils.AffectedFile;
import com.microfocus.performancecenter.integration.common.helpers.utils.AffectedFolder;
import com.microfocus.performancecenter.integration.common.helpers.utils.ModifiedFile;
import com.microfocus.performancecenter.integration.configuresystem.ConfigureSystemSection;
import com.microfocus.performancecenter.integration.pcgitsync.helper.UploadScriptMode;
import com.microfocus.performancecenter.integration.pcgitsync.helper.YesOrNo;
import hudson.FilePath;
import hudson.model.Result;
import hudson.model.TaskListener;
import hudson.remoting.VirtualChannel;
import jenkins.security.Roles;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.apache.commons.io.FilenameUtils;
import org.jenkinsci.remoting.RoleChecker;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static com.microfocus.performancecenter.integration.common.helpers.utils.LogHelper.log;
import static com.microfocus.performancecenter.integration.common.helpers.utils.LogHelper.logStackTrace;

@Getter
@RequiredArgsConstructor
public class PcGitSyncClient implements FilePath.FileCallable<Result>, Serializable {

    private final TaskListener listener;
    private final ConfigureSystemSection configureSystemSection;

    @Nullable
    private final Set<ModifiedFile> modifiedFiles;
    
    private final PcGitSyncModel pcGitSyncModel;
    private final UsernamePasswordCredentials usernamePCPasswordCredentials;
    private final UsernamePasswordCredentials usernamePCPasswordCredentialsForProxy;


    @Override
    public void checkRoles(RoleChecker rc) throws SecurityException {
        rc.check(this, Roles.SLAVE);
    }

    @Override
    public Result invoke(File workspace, VirtualChannel channel) throws IOException, InterruptedException {
        return start(workspace);
    }

    //main function
    public Result start(File workspace) throws IOException, InterruptedException {
        
        boolean deleteScripts = (this.pcGitSyncModel.getRemoveScriptFromPC() == YesOrNo.YES) ? true: false;

        Result result = Result.SUCCESS;
        PcRestProxy restProxy = defineRestProxy();
        if(restProxy == null)
            return Result.FAILURE;

        boolean loggedIn = false;

        boolean allowFolderCreation = false;

        try  {
            if (!validateParameters(listener)) {
                return Result.FAILURE;
            }

            Set<AffectedFolder> scriptsForDelete = null;
            Set<AffectedFolder> scriptsForUpload;
            Set<AffectedFile> testsToCreateOrUpdate = null;

            WorkspaceScripts wss = new WorkspaceScripts();
            WorkspaceTests wst = new WorkspaceTests();

            if (modifiedFiles == null) { // upload all scripts and all tests:

                scriptsForUpload = wss.getAllScriptsForUpload(workspace.toPath());
                if(pcGitSyncModel.getImportTests()!=null && pcGitSyncModel.getImportTests().equals(YesOrNo.YES))
                    testsToCreateOrUpdate = wst.getAllTestsToCreateOrUpdate(workspace.toPath());
            } else { // upload/delete only deltas taken from the changelog:

                if (!modifiedFiles.isEmpty()) {
                    log(listener, "", true);
                    logSetOfChangedFiles("List of files modified in GIT repository since last successful build:", modifiedFiles);
                    log(listener, "", true);

                    Set<AffectedFolder> affectedFolders = wss.getAllAffectedFolders(modifiedFiles, workspace.toPath());

                    logSetOfAffectedScripts("List of folders modified in GIT repository since last successful build:", affectedFolders);
                    log(listener, "", true);

                    if (deleteScripts) {
                        scriptsForDelete = wss.getAllScriptsForDelete(modifiedFiles, workspace.toPath());
                        logSetOfAffectedScripts("List of scripts deleted from Git that will be deleted from Performance Center:", scriptsForDelete);
                        log(listener, "", true);

                    }

                    scriptsForUpload = wss.getAllScriptsForUpload(affectedFolders, workspace.toPath());
                    logSetOfAffectedScripts("List of scripts added to Git that will be uploaded to Performance Center:", scriptsForUpload);
                    log(listener, "", true);

                    if(pcGitSyncModel.getImportTests()!=null && pcGitSyncModel.getImportTests().equals(YesOrNo.YES)) {
                        Set<AffectedFile> affectedFiles = wst.getAllAffectedFiles(modifiedFiles, workspace.toPath());
                        testsToCreateOrUpdate = wst.getAllTestsToCreateOrUpdate(affectedFiles, workspace.toPath());
                        logSetOfAffectedTests("List of tests added to Git that will be uploaded to Performance Center:", testsToCreateOrUpdate);
                    }
                } else {
                    log(listener, "No files were modified since the last successful build", true);
                    return result;
                }

            }
            log(listener, "", true);

            initMessage(listener,"Beginning to sync between GIT repository and Performance Center", true);

            loggedIn = login(restProxy);
            if (!loggedIn) {
                log(listener, "Login failed.", true);
                return Result.FAILURE;
            }
            log(listener, "The synchronization will be performed with PC project '%s' on domain '%s'.",
                    true,
                    pcGitSyncModel.getAlmProject(),
                    pcGitSyncModel.getAlmDomain()
                    );
            log(listener, "", false);

            allowFolderCreation = isAllowFolderCreation(restProxy);

            result = result.combine(deleteScriptsFromPerformanceCenter(scriptsForDelete, restProxy, allowFolderCreation));
            result = result.combine(uploadScriptsToPerformanceCenter(scriptsForUpload, restProxy, allowFolderCreation));
            if(pcGitSyncModel.getImportTests()!=null && pcGitSyncModel.getImportTests().equals(YesOrNo.YES))
                result = result.combine(createOrUpdateTestsInPerformanceCenter(testsToCreateOrUpdate, restProxy, allowFolderCreation));


        } catch (PcException ex) {
            log(listener, "Error PcException: %s.", true, ex.getMessage());
            logStackTrace(listener, configureSystemSection, ex);
            result = result.combine(Result.FAILURE);
        } catch (Exception ex) {
            log(listener, "General exception: %s.", true, ex.getMessage());
            logStackTrace(listener, configureSystemSection, ex);
            result = result.combine(Result.FAILURE);
        } finally {
            logout(loggedIn, restProxy);
        }

        return result;
    }

    private PcRestProxy defineRestProxy () {

        String proxyOutUser = (usernamePCPasswordCredentialsForProxy == null || pcGitSyncModel.getProxyOutURL(true).isEmpty()) ? "" : usernamePCPasswordCredentialsForProxy.getUsername();
        String proxyOutPassword= (usernamePCPasswordCredentialsForProxy == null || pcGitSyncModel.getProxyOutURL(true).isEmpty()) ? "" : usernamePCPasswordCredentialsForProxy.getPassword().getPlainText();
        PcRestProxy restProxy = null;
        try {
            restProxy = new PcRestProxy(pcGitSyncModel.getProtocol(), pcGitSyncModel.getPcServerName(true), pcGitSyncModel.getAlmDomain(true), pcGitSyncModel.getAlmProject(true), pcGitSyncModel.getProxyOutURL(true), proxyOutUser, proxyOutPassword);
        }catch (PcException e){
            log(listener,  String.format("Connection to PC server failed. Error: %s", e.getMessage()), true);
            logStackTrace(listener, configureSystemSection, e);
        }
        return restProxy;
    }


    private boolean login(PcRestProxy restProxy) {
        String pcUser =(usernamePCPasswordCredentials == null) ? "" : usernamePCPasswordCredentials.getUsername();
        String pcPassword= (usernamePCPasswordCredentials == null ) ? "" : usernamePCPasswordCredentials.getPassword().getPlainText();

        boolean loggedIn = false;
        try {
            initMessage(listener,"Login to Performance Center", false);

            if (pcGitSyncModel == null) {
                log(listener, String.format("pcGitSyncModel is null"), true);
            } else {
                log(
                        listener,
                        String.format("Login: Attempting to login to Performance Center server '%s://%s/LoadTest' with credentials of user '%s'",
                                pcGitSyncModel.getProtocol(),
                                pcGitSyncModel.getPcServerName(true),
                                pcUser
                        ),
                        true
                );
            }
            loggedIn = restProxy.authenticate(pcUser, pcPassword);
        } catch (PcException e) {
            log(listener, String.format("Login error PcException: %s", e.getMessage()), true);
            logStackTrace(listener, configureSystemSection, e);
        } catch (Exception e) {
            log(listener, String.format("Login error Exception: %s", e.getMessage()), true);
            logStackTrace(listener, configureSystemSection, e);
        } finally {
            log(listener, String.format("Login: %s", loggedIn ? "succeeded" : "failed"), true);
            log(listener, "", false);
        }
        return loggedIn;
    }

    private boolean isAllowFolderCreation (PcRestProxy restProxy) {
        boolean allowFolderCreation = false;
        try {
            PcTestPlanFolders pcTestPlanFolders = restProxy.getTestPlanFolders();
            if (pcTestPlanFolders != null) {
                log(listener, "Performance Center version 12.60 or above detected.", true);
                log(listener, "", false);
                allowFolderCreation = true;
            }
        } catch (PcException|IOException ex) {
            log(listener, "Cannot retrieve Test Plan folder tree which means one of the following: ", true);
            log(listener, "- Performance Center version 12.57 or below is used and this means that folders in Test Plan tree are required to be created manually.", true);
            log(listener, "- Domain and Project details are wrong.", true);

        }
        return allowFolderCreation;
    }

    public boolean logout(boolean loggedIn, PcRestProxy restProxy) {
        if (!loggedIn)
            return true;

        boolean logoutSucceeded = false;
        try {
            logoutSucceeded = restProxy.logout();
            log(listener, String.format("Logout: %s", logoutSucceeded ? "succeeded" : "failed"), true);
        } catch (PcException e) {
            log(listener,String.format("logout error PcException: %s. \n%s", e.getMessage(), e.getStackTrace()), true);
            logStackTrace(listener, configureSystemSection, e);
        } catch (Exception e) {
            log(listener, String.format("logout error Exception: %s. \n%s", e.getMessage(), e.getStackTrace()), true);
            logStackTrace(listener, configureSystemSection, e);
        }

        return logoutSucceeded;
    }


    private Result deleteScriptsFromPerformanceCenter(Set<AffectedFolder> scriptsForDelete, PcRestProxy restProxy, boolean allowFolderCreation) throws IOException, PcException {

        String subjectTestPlan = this.pcGitSyncModel.getSubjectTestPlan(true);

        if (scriptsForDelete == null || scriptsForDelete.isEmpty()) {
            return Result.SUCCESS;
        }

        initMessage(listener,"Deleting scripts", false);

        PcScripts scripts;
        try {
            scripts = Objects.requireNonNull(restProxy.getScripts());
        } catch (PcException|NullPointerException ex) {
            log(
                    listener, "An error occurred while getting the list of scripts from Performance Center. Error: %s.",
                    true,
                    ex.toString()
            );
            logStackTrace(listener, configureSystemSection, ex);
            return Result.SUCCESS;
        }

        //for every script to delete
        scriptsForDelete.forEach(localScript -> {
            scriptToDelete(restProxy, allowFolderCreation, subjectTestPlan, localScript);
        });


        log(listener, "Finished deleting scripts", true);
        log(listener, "", true);
        return Result.SUCCESS;
    }

    private void scriptToDelete(PcRestProxy restProxy, boolean allowFolderCreation, String subjectTestPlan, AffectedFolder localScript) {
        String targetSubject = allowFolderCreation ? localScript.getSubjectPath() : subjectTestPlan;
        Path localScriptRelativePath = localScript.getRelativePath();
        String localScriptName = localScriptRelativePath.getName(localScriptRelativePath.getNameCount() - 1).toString();
        try {

            log(
                    listener,
                    "Deleting script '%s\\%s' from Performance Center...",
                    true,
                    targetSubject,
                    localScriptName
            );

            PcScript pcScriptToDelete = getScript(targetSubject , localScriptName, restProxy);
            if (pcScriptToDelete instanceof  PcScript && pcScriptToDelete.getName().toLowerCase().equals(localScriptName.toLowerCase())) {
                deleteScript(restProxy, pcScriptToDelete);
            } else {
                log(
                        listener,
                        "---- This script was not found in Performance Center, therefore it cannot be deleted.",
                        false
                );
            }
        } catch (PcException ex) {
            log(
                    listener,
                    "**** Could not verify if the script exists. Error PcException: %s.",
                    false,
                    ex.toString()
            );
            logStackTrace(listener, configureSystemSection, ex);
            throw new RuntimeException(ex);
        } catch (IOException ex) {
            log(
                    listener,
                    "**** Could not verify if script exists. Error IOException: %s.",
                    false,
                    ex.toString()
            );
            logStackTrace(listener, configureSystemSection, ex);
            throw new RuntimeException(ex);
        } finally {
            log(
                    listener,
                    "",
                    false
            );
        }
    }

    private void deleteScript(PcRestProxy restProxy, PcScript pcScriptToDelete) {
        try {

            restProxy.deleteScript(pcScriptToDelete.getID());
            log(
                    listener,
                    "++++ Script deleted successfully.",
                    false
            );
        } catch (PcException|IOException ex) {
            log(
                    listener,
                    "**** Could not delete script. Error: %s",
                    false,
                    ex.getMessage()
            );
            logStackTrace(listener, configureSystemSection, ex);
            throw new RuntimeException(ex);
        }
    }

    public static void initMessage(TaskListener listener, String message, boolean doubleStarlineWithoutSpace) {
        String spaces = doubleStarlineWithoutSpace? "" : "         ";
        String stars = message.replaceAll(".", "*");
        if(doubleStarlineWithoutSpace) {
            log(listener,"", true);
            log(listener, spaces.concat(stars), false);
        }
        log(listener, spaces.concat(stars), false);
        log(listener, spaces.concat(message), false);
        log(listener, spaces.concat(stars), false);
        if(doubleStarlineWithoutSpace) {
            log(listener, spaces.concat(stars), false);
            log(listener,"", true);
        }
        log(listener, "", false);
    }

    public PcScript getScript(String testFolderPath, String scriptName, PcRestProxy restProxy) throws IOException,PcException{
        List<PcScript> pcScriptList = restProxy.getScripts().getPcScriptList();
        if (pcScriptList == null)
            return null;
        for (PcScript pcScript:pcScriptList
                ) {
            if(pcScript.getTestFolderPath().equalsIgnoreCase(testFolderPath.toLowerCase()) && pcScript.getName().equalsIgnoreCase(scriptName.toLowerCase())) {
                return pcScript;
            }
        }
        return null;
    }

    private Result createOrUpdateTestsInPerformanceCenter(Set<AffectedFile> testsToCreateOrUpdate, PcRestProxy restProxy, boolean allowFolderCreation) throws PcException, IOException {
        Result result = Result.SUCCESS;

        String subjectTestPlan = this.pcGitSyncModel.getSubjectTestPlan(true);

        if (testsToCreateOrUpdate == null || testsToCreateOrUpdate.isEmpty()) {
            return result;
        }

        uploadTestsInitialMessage();

        //for every script to add
        for(AffectedFile test : testsToCreateOrUpdate){
            result = result.combine(createOrUpdateTest(restProxy, allowFolderCreation, result, subjectTestPlan, test));
        }

        log(listener, "Finished creating or updating tests step.", true);
        log(listener, "", false);
        return result;
    }

    private Result uploadScriptsToPerformanceCenter(Set<AffectedFolder> scriptsForUpload, PcRestProxy restProxy, boolean allowFolderCreation) throws PcException, IOException {
        Result result = Result.SUCCESS;

        String subjectTestPlan = this.pcGitSyncModel.getSubjectTestPlan(true);
        boolean uploadRunTimeFiles = (this.pcGitSyncModel.getUploadScriptMode() == UploadScriptMode.RUNTIME_FILES)?true:false;
        
        if (scriptsForUpload == null || scriptsForUpload.isEmpty()) {
            return result;
        }

        uploadScriptsInitialMessage();
        ICompressor compressor = new Compressor();

        //for every script to add
        for(AffectedFolder script : scriptsForUpload){
            result = result.combine(uploadScript(restProxy, allowFolderCreation, result, subjectTestPlan, uploadRunTimeFiles, compressor, script));
        }

        log(listener, "Finished uploading scripts step.", true);
        log(listener, "", false);
        return result;
    }

    private Result uploadScript(PcRestProxy restProxy, boolean allowFolderCreation, Result result, String subjectTestPlan, boolean uploadRunTimeFiles, ICompressor compressor, AffectedFolder script) {
        try {
            String scriptFullPath = script.getFullPath().toString();
            String archive = scriptFullPath + ".zip";
            compressor.compressDirectoryToFile(scriptFullPath, archive, true, "JENKINS PLUGIN");
            String scriptRelativePath = script.getRelativePath().toString();

            String targetSubject = allowFolderCreation ? script.getSubjectPath() : subjectTestPlan;

            try {
                int scriptId =restProxy.uploadScript(targetSubject, true, uploadRunTimeFiles, true, archive);
                if (scriptId != 0) {
                    log(
                            listener,
                            "Uploading script '%s' from Git to Performance Center...",
                            true,
                            scriptRelativePath
                    );
                    PcScript pcScript = restProxy.getScript(scriptId);
                    log(
                            listener,
                            "+++++ Script uploaded successfully: '%s\\%s' (ID: %d, protocol: %s, mode: %s).",
                            false,
                            pcScript.getTestFolderPath(),
                            pcScript.getName(),
                            pcScript.getID(),
                            pcScript.getProtocol(),
                            pcScript.getWorkingMode()
                    );
                } else {
                    result = Result.FAILURE;
                    log(
                            listener,
                            "----- Failed to upload the script.",
                            false
                    );
                }

            } catch (PcException ex) {
                result = Result.FAILURE;
                log(
                        listener,
                        "***** Failed to upload the script. Error: %s.",
                        false,
                        ex.getMessage()
                );
                logStackTrace(listener, configureSystemSection, ex);
            }
        } catch (IOException ex) {
            result = Result.FAILURE;
            log(
                    listener,
                    "***** Failed to upload the script. Error IOException: %s.",
                    false,
                    ex.getMessage()
            );
            logStackTrace(listener, configureSystemSection, ex);
        }  finally {
            log(
                    listener,
                    "",
                    false
            );
        }
        return result;
    }

    private Result createOrUpdateTest(PcRestProxy restProxy, boolean allowFolderCreation, Result result, String subjectTestPlan, AffectedFile test) {
        try {
            String testFullPath = test.getFullPath().toString();
            String ext = FilenameUtils.getExtension(testFullPath);
            String testRelativePath = test.getRelativePath().toString();
            String targetSubject = allowFolderCreation ? test.getSubjectPath() : subjectTestPlan;
            String testFileContent = test.getTestContent();

            try {
                log(
                        listener,
                        "Creating or updating test '%s' from Git to Performance Center",
                        true,
                        test.getRelativePath().toString().concat("\\").concat(test.getFullPath().getFileName().toString())
                );
                Test createdTest = null;
                if(PcTestRunConstants.XML_EXTENSION.substring(1).equalsIgnoreCase(ext))
                    createdTest = restProxy.createOrUpdateTest(test.getTestName(), targetSubject, testFileContent);
                else if(PcTestRunConstants.YAML_EXTENSION.substring(1).equalsIgnoreCase(ext) || PcTestRunConstants.YML_EXTENSION.substring(1).equalsIgnoreCase(ext))
                    createdTest = restProxy.createOrUpdateTestFromYamlContent(test.getTestName(), targetSubject, testFileContent);
                if(createdTest == null) {
                    result = Result.FAILURE;
                    log(
                    listener,
                            "----- Test was not created/updated",
                            false
                    );
                    return result;
                }
                if (Common.stringToInteger(createdTest.getID()) > 0) {
                    log(
                            listener,
                            "+++++ Test created/updated successfully: '%s\\%s' (ID: %s).",
                            false,
                            createdTest.getTestFolderPath(),
                            createdTest.getName(),
                            createdTest.getID()
                    );
                } else {
                    result = Result.FAILURE;
                    log(
                            listener,
                            "----- Failed to create/update the test.",
                            false
                    );
                }

            } catch (PcException ex) {
                result = Result.FAILURE;
                log(
                        listener,
                        "***** Failed to create/update the test. Error: %s.",
                        false,
                        ex.getMessage()
                );
                logStackTrace(listener, configureSystemSection, ex);
            }
        } catch (IOException ex) {
            result = Result.FAILURE;
            log(
                    listener,
                    "***** Failed to create/update the script. Error: %s.",
                    false,
                    ex.getMessage()
            );
            logStackTrace(listener, configureSystemSection, ex);
        } finally {
            log(
                    listener,
                    "",
                    false
            );
        }
        return result;
    }


    private void uploadScriptsInitialMessage() {
        initMessage(listener,"Uploading scripts", false);

        log(listener, "(Each script folder will be automatically compressed in the workspace and then uploaded to the Performance Center project)", false);
        log(listener, "", false);
    }

    private void uploadTestsInitialMessage() {
        initMessage(listener,"Creating or updating Tests", false);

        log(listener, "(Each test file (yaml or xml) will be created or updated to the Performance Center project)", false);
        log(listener, "", false);
    }

    private void logSetOfChangedFiles(String header, Set<ModifiedFile> modifiedFiles) {
        log(listener, header, true);
        modifiedFiles.forEach(changedFile -> {
            log(listener, changedFile.toString(true), false);
        });
    }

    private void logSetOfAffectedScripts(String header, Set<AffectedFolder> affectedFiles) {
        log(listener, header, true);

        if (affectedFiles.isEmpty()) {
            log(listener, "(None)", false);
            return;
        }

        affectedFiles.forEach(affectedFile -> {
            log(listener, affectedFile.toString(true), false);
        });
    }

    private void logSetOfAffectedTests(String header, Set<AffectedFile> affectedFiles) {
        log(listener, header, true);

        if (affectedFiles.isEmpty()) {
            log(listener, "(None)", false);
            return;
        }

        affectedFiles.forEach(affectedFile -> {
            log(listener, affectedFile.toString(true), false);
        });
    }

    private boolean validateParameters(TaskListener listener) {

        String pcServerAndPort = this.pcGitSyncModel.getPcServerName(true);
        String domain = this.pcGitSyncModel.getAlmDomain(true);
        String project = this.pcGitSyncModel.getAlmProject(true);
        String subjectTestPlan = this.pcGitSyncModel.getSubjectTestPlan(true);
        String credentialsId = this.pcGitSyncModel.getCredentialsId(true);

        return verifyInputs(listener, pcServerAndPort, domain, project, subjectTestPlan, credentialsId);
    }

    private boolean verifyInputs(TaskListener listener, String pcServerAndPort, String domain, String project, String subjectTestPlan, String credentialsId) {
        String message = "";
        if (pcServerAndPort == null || pcServerAndPort.isEmpty()) {
            message = "Performance Center Server not specified. ";
        }

        if (domain == null || domain.isEmpty()) {
            message = message.concat("No domain specified. ");
        }

        if (project == null || project.isEmpty()) {
            message = message.concat("No project specified. ");
        }

        if (subjectTestPlan == null || subjectTestPlan.isEmpty()) {
            message = message.concat("The path to the folder in the Test Plan is not specified. ");
        }

        if (!subjectTestPlan.startsWith("Subject")) {
            message = message.concat(String.format("The path to the folder '% s' should start with 'Subject\\'. ", subjectTestPlan));
        }

        if (credentialsId == null || pcServerAndPort.isEmpty()) {
            message = message.concat("No credentials specified. ");
        }

        if(!message.isEmpty()) {
            listener.error(message);
            return false;
        }

        return true;
    }

}
