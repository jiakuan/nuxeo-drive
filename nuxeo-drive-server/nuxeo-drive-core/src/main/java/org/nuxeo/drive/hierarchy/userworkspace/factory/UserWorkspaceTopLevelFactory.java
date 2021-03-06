/*
 * (C) Copyright 2013 Nuxeo SA (http://nuxeo.com/) and contributors.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser General Public License
 * (LGPL) version 2.1 which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl-2.1.html
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * Contributors:
 *     Antoine Taillefer <ataillefer@nuxeo.com>
 */
package org.nuxeo.drive.hierarchy.userworkspace.factory;

import java.security.Principal;
import java.util.Map;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.nuxeo.drive.adapter.FileSystemItem;
import org.nuxeo.drive.adapter.FolderItem;
import org.nuxeo.drive.hierarchy.userworkspace.adapter.UserWorkspaceHelper;
import org.nuxeo.drive.hierarchy.userworkspace.adapter.UserWorkspaceTopLevelFolderItem;
import org.nuxeo.drive.service.FileSystemItemManager;
import org.nuxeo.drive.service.TopLevelFolderItemFactory;
import org.nuxeo.drive.service.impl.AbstractFileSystemItemFactory;
import org.nuxeo.ecm.core.api.ClientException;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.repository.RepositoryManager;
import org.nuxeo.ecm.platform.userworkspace.api.UserWorkspaceService;
import org.nuxeo.runtime.api.Framework;

/**
 * User workspace based implementation of the {@link TopLevelFolderItemFactory}.
 *
 * @author Antoine Taillefer
 */
public class UserWorkspaceTopLevelFactory extends AbstractFileSystemItemFactory
        implements TopLevelFolderItemFactory {

    private static final Log log = LogFactory.getLog(UserWorkspaceTopLevelFactory.class);

    protected static final String FOLDER_NAME_PARAM = "folderName";

    protected static final String SYNC_ROOT_PARENT_FACTORY_PARAM = "syncRootParentFactory";

    protected static final String DEFAULT_FOLDER_NAME = "Nuxeo Drive";

    protected String folderName = DEFAULT_FOLDER_NAME;

    protected String syncRootParentFactoryName;

    /*---------------------- AbstractFileSystemItemFactory ---------------*/
    @Override
    public void handleParameters(Map<String, String> parameters)
            throws ClientException {
        // Look for the "folderName" parameter
        String folderNameParam = parameters.get(FOLDER_NAME_PARAM);
        if (!StringUtils.isEmpty(folderNameParam)) {
            folderName = folderNameParam;
        } else {
            log.info(String.format(
                    "Factory %s has no %s parameter, you can provide one in the factory contribution to avoid using the default value '%s'.",
                    getName(), FOLDER_NAME_PARAM, DEFAULT_FOLDER_NAME));
        }
        // Look for the "syncRootParentFactory" parameter
        String syncRootParentFactoryParam = parameters.get(SYNC_ROOT_PARENT_FACTORY_PARAM);
        if (!StringUtils.isEmpty(syncRootParentFactoryParam)) {
            syncRootParentFactoryName = syncRootParentFactoryParam;
        } else {
            log.warn(String.format(
                    "Factory %s has no %s parameter, please provide one in the factory contribution to set the name of the synchronization root parent factory.",
                    getName(), SYNC_ROOT_PARENT_FACTORY_PARAM));
        }
    }

    @Override
    public boolean isFileSystemItem(DocumentModel doc, boolean includeDeleted,
            boolean relaxSyncRootConstraint) throws ClientException {
        // Check user workspace
        boolean isUserWorkspace = UserWorkspaceHelper.isUserWorkspace(doc);
        if (!isUserWorkspace) {
            log.trace(String.format(
                    "Document %s is not a user workspace, it cannot be adapted as a FileSystemItem.",
                    doc.getId()));
            return false;
        }
        return true;
    }

    @Override
    protected FileSystemItem adaptDocument(DocumentModel doc,
            boolean forceParentItem, FolderItem parentItem,
            boolean relaxSyncRootConstraint) throws ClientException {
        return new UserWorkspaceTopLevelFolderItem(getName(), doc, folderName,
                syncRootParentFactoryName);
    }

    /*---------------------- VirtualFolderItemFactory ---------------*/
    @Override
    public FolderItem getVirtualFolderItem(Principal principal)
            throws ClientException {
        return getTopLevelFolderItem(principal);
    }

    @Override
    public String getFolderName() {
        return folderName;
    }

    @Override
    public void setFolderName(String folderName) {
        this.folderName = folderName;
    }

    /*----------------------- TopLevelFolderItemFactory ---------------------*/
    @Override
    public FolderItem getTopLevelFolderItem(Principal principal)
            throws ClientException {
        DocumentModel userWorkspace = getUserPersonalWorkspace(principal);
        return (FolderItem) getFileSystemItem(userWorkspace);
    }

    /*------------------- Protected ------------------- */
    protected DocumentModel getUserPersonalWorkspace(Principal principal)
            throws ClientException {
        UserWorkspaceService userWorkspaceService = Framework.getLocalService(UserWorkspaceService.class);
        RepositoryManager repositoryManager = Framework.getLocalService(RepositoryManager.class);
        // TODO: handle multiple repositories
        CoreSession session = getSession(
                repositoryManager.getDefaultRepository().getName(), principal);
        DocumentModel userWorkspace = userWorkspaceService.getCurrentUserPersonalWorkspace(
                session, null);
        if (userWorkspace == null) {
            throw new ClientException(String.format(
                    "No personal workspace found for user %s.",
                    principal.getName()));
        }
        return userWorkspace;
    }

    protected CoreSession getSession(String repositoryName, Principal principal)
            throws ClientException {
        return getFileSystemItemManager().getSession(repositoryName, principal);
    }

    protected FileSystemItemManager getFileSystemItemManager() {
        return Framework.getLocalService(FileSystemItemManager.class);
    }

}
