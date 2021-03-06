/*
 * (C) Copyright 2012 Nuxeo SA (http://nuxeo.com/) and contributors.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser General Public License
 * (LGPL) version 2.1 which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl.html
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * Contributors:
 *     Antoine Taillefer <ataillefer@nuxeo.com>
 */
package org.nuxeo.drive.hierarchy.permission.factory;

import java.security.Principal;
import java.util.Map;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.nuxeo.drive.adapter.FileSystemItem;
import org.nuxeo.drive.adapter.FolderItem;
import org.nuxeo.drive.adapter.impl.DefaultSyncRootFolderItem;
import org.nuxeo.drive.service.FileSystemItemAdapterService;
import org.nuxeo.drive.service.FileSystemItemFactory;
import org.nuxeo.drive.service.impl.AbstractSyncRootFolderItemFactory;
import org.nuxeo.ecm.core.api.ClientException;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.security.SecurityConstants;
import org.nuxeo.runtime.api.Framework;

/**
 * Permission based implementation of {@link FileSystemItemFactory} for a
 * synchronization root {@link FolderItem}.
 *
 * @author Antoine Taillefer
 */
public class PermissionSyncRootFactory extends
        AbstractSyncRootFolderItemFactory {

    private static final Log log = LogFactory.getLog(PermissionSyncRootFactory.class);

    protected static final String REQUIRED_PERMISSION_PARAM = "requiredPermission";

    protected static final String USER_SYNC_ROOT_PARENT_FACTORY_PARAM = "userSyncRootParentFactory";

    protected static final String SHARED_SYNC_ROOT_PARENT_FACTORY_PARAM = "sharedSyncRootParentFactory";

    // Required permission to include a folder as a synchronization root,
    // default is ReadWrite
    protected String requiredPermission = SecurityConstants.READ_WRITE;

    protected String userSyncRootParentFactoryName;

    protected String sharedSyncRootParentFactoryName;

    /*------------------- AbstractFileSystemItemFactory ---------------------*/
    @Override
    public void handleParameters(Map<String, String> parameters)
            throws ClientException {
        String requiredPermissionParam = parameters.get(REQUIRED_PERMISSION_PARAM);
        if (!StringUtils.isEmpty(requiredPermissionParam)) {
            requiredPermission = requiredPermissionParam;
        }

        String userSyncRootParentFactoryParam = parameters.get(USER_SYNC_ROOT_PARENT_FACTORY_PARAM);
        if (StringUtils.isEmpty(userSyncRootParentFactoryParam)) {
            throw new ClientException(
                    String.format(
                            "Factory %s has no %s parameter, please provide it in the factory contribution to set the name of the factory for the parent folder of the user's synchronization roots.",
                            getName(), USER_SYNC_ROOT_PARENT_FACTORY_PARAM));
        }
        userSyncRootParentFactoryName = userSyncRootParentFactoryParam;

        String sharedSyncRootParentFactoryParam = parameters.get(SHARED_SYNC_ROOT_PARENT_FACTORY_PARAM);
        if (StringUtils.isEmpty(sharedSyncRootParentFactoryParam)) {
            throw new ClientException(
                    String.format(
                            "Factory %s has no %s parameter, please provide it in the factory contribution to set the name of the factory for the parent folder of the user's shared synchronization roots.",
                            getName(), SHARED_SYNC_ROOT_PARENT_FACTORY_PARAM));
        }
        sharedSyncRootParentFactoryName = sharedSyncRootParentFactoryParam;
    }

    /**
     * Checks if the given {@link DocumentModel} is adaptable as a
     * {@link FileSystemItem}.
     *
     * The permission synchronization root factory considers that a
     * {@link DocumentModel} is adaptable as a {@link FileSystemItem} if:
     * <ul>
     * <li>It is Folderish</li>
     * <li>AND it is not a version nor a proxy</li>
     * <li>AND it is not HiddenInNavigation</li>
     * <li>AND it is not in the "deleted" life cycle state, unless
     * {@code includeDeleted} is true</li>
     * <li>AND it is a synchronization root registered for the current user,
     * unless {@code relaxSyncRootConstraint} is true</li>
     * <li>AND the current user has the {@link #getRequiredPermission()}
     * permission on the document</li>
     * </ul>
     */
    @Override
    public boolean isFileSystemItem(DocumentModel doc, boolean includeDeleted,
            boolean relaxSyncRootConstraint) throws ClientException {
        // Check required permission
        CoreSession session = doc.getCoreSession();
        boolean hasRequiredPermission = session.hasPermission(doc.getRef(),
                requiredPermission);
        if (!hasRequiredPermission) {
            log.debug(String.format(
                    "Required permission %s is not granted on document %s to user %s, it cannot be adapted as a FileSystemItem.",
                    requiredPermission, doc.getId(),
                    session.getPrincipal().getName()));
            return false;
        }
        return super.isFileSystemItem(doc, includeDeleted,
                relaxSyncRootConstraint) && hasRequiredPermission;
    }

    @Override
    protected FileSystemItem adaptDocument(DocumentModel doc,
            boolean forceParentId, FolderItem parentItem,
            boolean relaxSyncRootConstraint) throws ClientException {
        return new DefaultSyncRootFolderItem(name, parentItem, doc);
    }

    /*------------------ AbstractSyncRootFolderItemFactory ------------------*/
    @Override
    protected FolderItem getParentItem(DocumentModel doc)
            throws ClientException {
        Principal principal = doc.getCoreSession().getPrincipal();
        String docCreator = (String) doc.getPropertyValue("dc:creator");
        if (principal.getName().equals(docCreator)) {
            FolderItem parent = getFileSystemAdapterService().getVirtualFolderItemFactory(
                    userSyncRootParentFactoryName).getVirtualFolderItem(
                    principal);
            if (parent == null) {
                throw new ClientException(
                        String.format(
                                "Cannot find the parent of document %s: virtual folder from factory %s.",
                                doc.getId(), userSyncRootParentFactoryName));
            }
            return parent;
        } else {
            FolderItem parent = getFileSystemAdapterService().getVirtualFolderItemFactory(
                    sharedSyncRootParentFactoryName).getVirtualFolderItem(
                    principal);
            if (parent == null) {
                throw new ClientException(
                        String.format(
                                "Cannot find the parent of document %s: virtual folder from factory %s.",
                                doc.getId(), sharedSyncRootParentFactoryName));
            }
            return parent;
        }
    }

    protected FileSystemItemAdapterService getFileSystemAdapterService() {
        return Framework.getLocalService(FileSystemItemAdapterService.class);
    }

}
