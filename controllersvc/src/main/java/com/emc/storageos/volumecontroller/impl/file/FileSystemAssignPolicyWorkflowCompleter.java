/*
 * Copyright (c) 2017 EMC Corporation
 * All Rights Reserved
 */
package com.emc.storageos.volumecontroller.impl.file;

import java.net.URI;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.emc.storageos.db.client.DbClient;
import com.emc.storageos.db.client.model.FilePolicy;
import com.emc.storageos.db.client.model.FilePolicy.FilePolicyType;
import com.emc.storageos.db.client.model.FileShare;
import com.emc.storageos.db.client.model.FileShare.MirrorStatus;
import com.emc.storageos.db.client.model.FileShare.PersonalityTypes;
import com.emc.storageos.db.client.model.Operation;
import com.emc.storageos.svcs.errorhandling.model.ServiceCoded;

/**
 * Task Completer class for assign file policy to existing file system.
 * 
 * @author Mudit Jain
 */
public class FileSystemAssignPolicyWorkflowCompleter extends FileWorkflowCompleter {

    private static final long serialVersionUID = 1L;
    protected static final Logger _log = LoggerFactory.getLogger(FilePolicyAssignWorkflowCompleter.class);
    private URI filePolicyURI;

    public FileSystemAssignPolicyWorkflowCompleter(URI filePolicy, List<URI> fsURIs, String task) {
        super(fsURIs, task);
        this.filePolicyURI = filePolicy;
    }

    @Override
    protected void complete(DbClient dbClient, Operation.Status status, ServiceCoded serviceCoded) {

        if (Operation.Status.ready.equals(status)) {
            FileShare fileSystem = getSourceFileSystem(dbClient);
            if (fileSystem != null) {
                fileSystem.setMirrorStatus(MirrorStatus.UNKNOWN.name());
                dbClient.updateObject(fileSystem);
                _log.info("CreateMirrorFileSystemsCompleter::Set the mirror status of source file system {}",
                        fileSystem.getId());
            }
            updateFilePolicyResource(dbClient);
        }
        super.complete(dbClient, status, serviceCoded);
    }

    private FileShare getSourceFileSystem(DbClient dbClient) {
        FileShare fs = null;

        for (URI id : getIds()) {
            FileShare fileSystem = dbClient.queryObject(FileShare.class, id);
            if (fileSystem != null && !fileSystem.getInactive() && fileSystem.getPersonality() != null &&
                    PersonalityTypes.SOURCE.name().equalsIgnoreCase(fileSystem.getPersonality())) {
                fs = fileSystem;
            }
        }

        return fs;
    }

    private void updateFilePolicyResource(DbClient dbClient) {
        FilePolicy filePolicy = dbClient.queryObject(FilePolicy.class, filePolicyURI);
        if (filePolicy.getFilePolicyType().equals(FilePolicyType.file_snapshot.name())) {
            filePolicy.addAssignedResources(getIds().get(0));
            FileShare fileSystem = dbClient.queryObject(FileShare.class, getIds().get(0));
            fileSystem.addFilePolicy(filePolicyURI);
            dbClient.updateObject(filePolicy);
            dbClient.updateObject(fileSystem);
        } else {
            FileShare fileSystem = getSourceFileSystem(dbClient);
            if (fileSystem != null) {
                filePolicy.addAssignedResources(fileSystem.getId());
                fileSystem.addFilePolicy(filePolicyURI);
                dbClient.updateObject(filePolicy);
                dbClient.updateObject(fileSystem);
            }
        }
    }
}