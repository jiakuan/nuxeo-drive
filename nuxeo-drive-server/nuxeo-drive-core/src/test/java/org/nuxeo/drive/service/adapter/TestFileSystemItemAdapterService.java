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
package org.nuxeo.drive.service.adapter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuxeo.drive.adapter.FileItem;
import org.nuxeo.drive.adapter.FileSystemItem;
import org.nuxeo.drive.adapter.FolderItem;
import org.nuxeo.drive.service.FileSystemItemAdapterService;
import org.nuxeo.drive.service.FileSystemItemFactory;
import org.nuxeo.drive.service.NuxeoDriveManager;
import org.nuxeo.drive.service.TopLevelFolderItemFactory;
import org.nuxeo.drive.service.VersioningFileSystemItemFactory;
import org.nuxeo.drive.service.VirtualFolderItemFactory;
import org.nuxeo.drive.service.impl.DefaultFileSystemItemFactory;
import org.nuxeo.drive.service.impl.DefaultSyncRootFolderItemFactory;
import org.nuxeo.drive.service.impl.DefaultTopLevelFolderItemFactory;
import org.nuxeo.drive.service.impl.FileSystemItemAdapterServiceImpl;
import org.nuxeo.drive.service.impl.FileSystemItemFactoryDescriptor;
import org.nuxeo.drive.service.impl.FileSystemItemFactoryWrapper;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.ClientException;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.VersioningOption;
import org.nuxeo.ecm.core.api.impl.blob.StringBlob;
import org.nuxeo.ecm.core.test.CoreFeature;
import org.nuxeo.ecm.core.test.TransactionalFeature;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.reload.ReloadService;
import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;
import org.nuxeo.runtime.test.runner.LocalDeploy;
import org.nuxeo.runtime.test.runner.RuntimeHarness;

import com.google.inject.Inject;

/**
 * Tests the {@link FileSystemItemAdapterService}.
 *
 * @author Antoine Taillefer
 */
@RunWith(FeaturesRunner.class)
@Features({ TransactionalFeature.class, CoreFeature.class })
@Deploy({ "org.nuxeo.drive.core", "org.nuxeo.runtime.reload" })
@LocalDeploy({
        "org.nuxeo.drive.core:OSGI-INF/test-nuxeodrive-types-contrib.xml",
        "org.nuxeo.drive.core:OSGI-INF/test-nuxeodrive-adapter-service-contrib.xml" })
public class TestFileSystemItemAdapterService {

    @Inject
    protected CoreSession session;

    @Inject
    protected FileSystemItemAdapterService fileSystemItemAdapterService;

    @Inject
    protected RuntimeHarness harness;

    protected String syncRootItemId;

    protected FolderItem syncRootItem;

    protected DocumentModel file;

    protected DocumentModel folder;

    protected DocumentModel custom;

    protected DocumentModel syncRootFolder;

    @Before
    public void registerRootAndCreateSomeDocs() throws Exception {

        syncRootFolder = session.createDocumentModel("/", "syncRoot", "Folder");
        syncRootFolder = session.createDocument(syncRootFolder);

        // Register the root folder as sync root
        NuxeoDriveManager driveManager = Framework.getLocalService(NuxeoDriveManager.class);
        driveManager.registerSynchronizationRoot(session.getPrincipal(),
                syncRootFolder, session);

        syncRootItem = (FolderItem) fileSystemItemAdapterService.getFileSystemItem(syncRootFolder);
        syncRootItemId = syncRootItem.getId();

        file = session.createDocumentModel(syncRootFolder.getPathAsString(),
                "aFile", "File");
        file.setPropertyValue("dc:creator", "Joe");
        Blob blob = new StringBlob("Content of Joe's file.");
        blob.setFilename("Joe's file.txt");
        file.setPropertyValue("file:content", (Serializable) blob);
        file = session.createDocument(file);

        folder = session.createDocumentModel(syncRootFolder.getPathAsString(),
                "aFolder", "Folder");
        folder.setPropertyValue("dc:title", "Jack's folder");
        folder.setPropertyValue("dc:creator", "Jack");
        folder = session.createDocument(folder);

        custom = session.createDocumentModel(syncRootFolder.getPathAsString(),
                "aCustom", "Custom");
        custom.setPropertyValue("dc:creator", "Bonnie");
        blob = new StringBlob("Content of the custom document's blob.");
        blob.setFilename("Bonnie's file.txt");
        custom.setPropertyValue("file:content", (Serializable) blob);
        custom = session.createDocument(custom);

        session.save();
    }

    @Test
    public void testService() throws Exception {

        // ------------------------------------------------------
        // Check file system item factory descriptors
        // ------------------------------------------------------
        Map<String, FileSystemItemFactoryDescriptor> fileSystemItemFactoryDescs = ((FileSystemItemAdapterServiceImpl) fileSystemItemAdapterService).getFileSystemItemFactoryDescriptors();
        assertNotNull(fileSystemItemFactoryDescs);
        assertEquals(11, fileSystemItemFactoryDescs.size());

        FileSystemItemFactoryDescriptor desc = fileSystemItemFactoryDescs.get("defaultSyncRootFolderItemFactory");
        assertNotNull(desc);
        assertEquals(10, desc.getOrder());
        assertEquals("defaultSyncRootFolderItemFactory", desc.getName());
        assertNull(desc.getDocType());
        assertEquals("DriveSynchronized", desc.getFacet());
        FileSystemItemFactory factory = desc.getFactory();
        assertTrue(factory instanceof DefaultSyncRootFolderItemFactory);

        desc = fileSystemItemFactoryDescs.get("dummyDocTypeFactory");
        assertNotNull(desc);
        assertEquals(20, desc.getOrder());
        assertEquals("dummyDocTypeFactory", desc.getName());
        assertEquals("File", desc.getDocType());
        assertNull(desc.getFacet());
        factory = desc.getFactory();
        assertTrue(factory instanceof DummyFileItemFactory);
        assertTrue(factory instanceof VersioningFileSystemItemFactory);
        assertEquals(
                2.0,
                ((VersioningFileSystemItemFactory) factory).getVersioningDelay(),
                .01);
        assertEquals(
                VersioningOption.MINOR,
                ((VersioningFileSystemItemFactory) factory).getVersioningOption());

        desc = fileSystemItemFactoryDescs.get("dummyFacetFactory");
        assertNotNull(desc);
        assertEquals(30, desc.getOrder());
        assertEquals("dummyFacetFactory", desc.getName());
        assertNull(desc.getDocType());
        assertEquals("Folderish", desc.getFacet());
        factory = desc.getFactory();
        assertTrue(factory instanceof DummyFolderItemFactory);

        desc = fileSystemItemFactoryDescs.get("defaultFileSystemItemFactory");
        assertNotNull(desc);
        assertEquals(50, desc.getOrder());
        assertEquals("defaultFileSystemItemFactory", desc.getName());
        assertNull(desc.getDocType());
        assertNull(desc.getFacet());
        factory = desc.getFactory();
        assertTrue(factory instanceof DefaultFileSystemItemFactory);
        assertTrue(factory instanceof VersioningFileSystemItemFactory);
        assertEquals(
                3600.0,
                ((VersioningFileSystemItemFactory) factory).getVersioningDelay(),
                .01);
        assertEquals(
                VersioningOption.MINOR,
                ((VersioningFileSystemItemFactory) factory).getVersioningOption());

        desc = fileSystemItemFactoryDescs.get("dummyVirtualFolderItemFactory");
        assertNotNull(desc);
        assertEquals(100, desc.getOrder());
        assertEquals("dummyVirtualFolderItemFactory", desc.getName());
        assertNull(desc.getDocType());
        assertNull(desc.getFacet());
        factory = desc.getFactory();
        assertTrue(factory instanceof VirtualFolderItemFactory);
        assertEquals("Dummy Folder",
                ((VirtualFolderItemFactory) factory).getFolderName());

        desc = fileSystemItemFactoryDescs.get("nullMergeTestFactory");
        assertNotNull(desc);
        assertEquals(200, desc.getOrder());
        assertEquals("nullMergeTestFactory", desc.getName());
        assertEquals("Note", desc.getDocType());
        assertNull(desc.getFacet());
        factory = desc.getFactory();
        assertTrue(factory instanceof DummyFileItemFactory);

        // ------------------------------------------------------
        // Check ordered file system item factories
        // ------------------------------------------------------
        List<FileSystemItemFactoryWrapper> fileSystemItemFactories = ((FileSystemItemAdapterServiceImpl) fileSystemItemAdapterService).getFileSystemItemFactories();
        assertNotNull(fileSystemItemFactories);
        assertEquals(6, fileSystemItemFactories.size());

        FileSystemItemFactoryWrapper factoryWrapper = fileSystemItemFactories.get(0);
        assertNotNull(factoryWrapper);
        assertNull(factoryWrapper.getDocType());
        assertEquals("DriveSynchronized", factoryWrapper.getFacet());
        assertTrue(factoryWrapper.getFactory().getClass().getName().endsWith(
                "DefaultSyncRootFolderItemFactory"));

        factoryWrapper = fileSystemItemFactories.get(1);
        assertNotNull(factoryWrapper);
        assertEquals("File", factoryWrapper.getDocType());
        assertNull(factoryWrapper.getFacet());
        assertTrue(factoryWrapper.getFactory().getClass().getName().endsWith(
                "DummyFileItemFactory"));

        factoryWrapper = fileSystemItemFactories.get(2);
        assertNotNull(factoryWrapper);
        assertNull(factoryWrapper.getDocType());
        assertEquals("Folderish", factoryWrapper.getFacet());
        assertTrue(factoryWrapper.getFactory().getClass().getName().endsWith(
                "DummyFolderItemFactory"));

        factoryWrapper = fileSystemItemFactories.get(3);
        assertNotNull(factoryWrapper);
        assertNull(factoryWrapper.getDocType());
        assertNull(factoryWrapper.getFacet());
        assertTrue(factoryWrapper.getFactory().getClass().getName().endsWith(
                "DefaultFileSystemItemFactory"));

        factoryWrapper = fileSystemItemFactories.get(4);
        assertNotNull(factoryWrapper);
        assertNull(factoryWrapper.getDocType());
        assertNull(factoryWrapper.getFacet());
        assertTrue(factoryWrapper.getFactory().getClass().getName().endsWith(
                "DummyVirtualFolderItemFactory"));

        factoryWrapper = fileSystemItemFactories.get(5);
        assertNotNull(factoryWrapper);
        assertEquals("Note", factoryWrapper.getDocType());
        assertNull(factoryWrapper.getFacet());
        assertTrue(factoryWrapper.getFactory().getClass().getName().endsWith(
                "DummyFileItemFactory"));

        // ------------------------------------------------------
        // Check #getFileSystemItem(DocumentModel doc)
        // ------------------------------------------------------
        // File => should use the dummyDocTypeFactory bound to the
        // DummyFileItemFactory class
        FileSystemItem fsItem = fileSystemItemAdapterService.getFileSystemItem(file);
        assertNotNull(fsItem);
        assertTrue(fsItem instanceof DummyFileItem);
        assertEquals("dummyDocTypeFactory#test#" + file.getId(), fsItem.getId());
        assertEquals(syncRootItemId, fsItem.getParentId());
        assertEquals("Dummy file with id " + file.getId(), fsItem.getName());
        assertFalse(fsItem.isFolder());
        assertEquals("Joe", fsItem.getCreator());

        // Folder => should use the dummyFacetFactory bound to the
        // DummyFolderItemFactory class
        fsItem = fileSystemItemAdapterService.getFileSystemItem(folder);
        assertNotNull(fsItem);
        assertTrue(fsItem instanceof DummyFolderItem);
        assertTrue(((FolderItem) fsItem).getCanCreateChild());
        assertEquals("dummyFacetFactory#test#" + folder.getId(), fsItem.getId());
        assertEquals(syncRootItemId, fsItem.getParentId());
        assertEquals("Dummy folder with id " + folder.getId(), fsItem.getName());
        assertTrue(fsItem.isFolder());
        assertEquals("Jack", fsItem.getCreator());

        // Custom => should use the defaultFileSystemItemFactory bound to the
        // DefaultFileSystemItemFactory class
        fsItem = fileSystemItemAdapterService.getFileSystemItem(custom);
        assertNotNull(fsItem);
        assertTrue(fsItem instanceof FileItem);
        assertEquals("defaultFileSystemItemFactory#test#" + custom.getId(),
                fsItem.getId());
        assertEquals(
                "/org.nuxeo.drive.service.impl.DefaultTopLevelFolderItemFactory#/"
                        + syncRootItemId + "/" + fsItem.getId(),
                fsItem.getPath());
        assertEquals(syncRootItemId, fsItem.getParentId());
        assertEquals("Bonnie's file.txt", fsItem.getName());
        assertFalse(fsItem.isFolder());
        assertEquals("Bonnie", fsItem.getCreator());
        Blob fileFsItemBlob = ((FileItem) fsItem).getBlob();
        assertEquals("Bonnie's file.txt", fileFsItemBlob.getFilename());
        assertEquals("Content of the custom document's blob.",
                fileFsItemBlob.getString());

        // ------------------------------------------------------
        // Check #getFileSystemItem(DocumentModel doc, FolderItem parentItem)
        // ------------------------------------------------------
        // File => should use the dummyDocTypeFactory bound to the
        // DummyFileItemFactory class
        fsItem = fileSystemItemAdapterService.getFileSystemItem(file,
                syncRootItem);
        assertNotNull(fsItem);
        assertEquals(syncRootItemId, fsItem.getParentId());

        // -------------------------------------------------------------
        // Check #getFileSystemItemFactoryForId(String id)
        // -------------------------------------------------------------
        // Default factory
        String fsItemId = "defaultFileSystemItemFactory#test#someId";
        FileSystemItemFactory fsItemFactory = fileSystemItemAdapterService.getFileSystemItemFactoryForId(fsItemId);
        assertNotNull(fsItemFactory);
        assertEquals("defaultFileSystemItemFactory", fsItemFactory.getName());
        assertTrue(fsItemFactory.getClass().getName().endsWith(
                "DefaultFileSystemItemFactory"));
        assertTrue(fsItemFactory.canHandleFileSystemItemId(fsItemId));

        // Top level folder item factory
        fsItemId = "org.nuxeo.drive.service.impl.DefaultTopLevelFolderItemFactory#";
        fsItemFactory = fileSystemItemAdapterService.getFileSystemItemFactoryForId(fsItemId);
        assertNotNull(fsItemFactory);
        assertTrue(fsItemFactory.getName().endsWith(
                "DefaultTopLevelFolderItemFactory"));
        assertTrue(fsItemFactory.getClass().getName().endsWith(
                "DefaultTopLevelFolderItemFactory"));
        assertTrue(fsItemFactory.canHandleFileSystemItemId(fsItemId));

        // Factory with #canHandleFileSystemItemId returning false
        fsItemId = "dummyDocTypeFactory#test#someId";
        try {
            fileSystemItemAdapterService.getFileSystemItemFactoryForId(fsItemId);
            fail("No fileSystemItemFactory should be found FileSystemItem id.");
        } catch (ClientException e) {
            assertEquals(
                    "No fileSystemItemFactory found for FileSystemItem with id dummyDocTypeFactory#test#someId. Please check the contributions to the following extension point: <extension target=\"org.nuxeo.drive.service.FileSystemItemAdapterService\" point=\"fileSystemItemFactory\"> and make sure there is at least one defining a FileSystemItemFactory class for which the #canHandleFileSystemItemId(String id) method returns true.",
                    e.getMessage());
        }

        // Non parsable id
        fsItemId = "nonParsableId";
        try {
            fileSystemItemAdapterService.getFileSystemItemFactoryForId(fsItemId);
            fail("No fileSystemItemFactory should be found for FileSystemItem id.");
        } catch (ClientException e) {
            assertEquals(
                    "No fileSystemItemFactory found for FileSystemItem with id nonParsableId. Please check the contributions to the following extension point: <extension target=\"org.nuxeo.drive.service.FileSystemItemAdapterService\" point=\"fileSystemItemFactory\"> and make sure there is at least one defining a FileSystemItemFactory class for which the #canHandleFileSystemItemId(String id) method returns true.",
                    e.getMessage());
        }

        // Non existent factory name
        fsItemId = "nonExistentFactoryName#test#someId";
        try {
            fileSystemItemAdapterService.getFileSystemItemFactoryForId(fsItemId);
            fail("No fileSystemItemFactory should be found for FileSystemItem id.");
        } catch (ClientException e) {
            assertEquals(
                    "No fileSystemItemFactory found for FileSystemItem with id nonExistentFactoryName#test#someId. Please check the contributions to the following extension point: <extension target=\"org.nuxeo.drive.service.FileSystemItemAdapterService\" point=\"fileSystemItemFactory\"> and make sure there is at least one defining a FileSystemItemFactory class for which the #canHandleFileSystemItemId(String id) method returns true.",
                    e.getMessage());
        }

        // -------------------------------------------------------------
        // Check #getTopLevelFolderItemFactory()
        // -------------------------------------------------------------
        TopLevelFolderItemFactory topLevelFactory = fileSystemItemAdapterService.getTopLevelFolderItemFactory();
        assertNotNull(topLevelFactory);
        assertTrue(topLevelFactory.getClass().getName().endsWith(
                "DefaultTopLevelFolderItemFactory"));
        assertTrue(topLevelFactory instanceof DefaultTopLevelFolderItemFactory);

        // -------------------------------------------------------------
        // Check #getVirtualFolderItemFactory(String factoryName)
        // -------------------------------------------------------------
        try {
            fileSystemItemAdapterService.getVirtualFolderItemFactory("nonExistentFactory");
            fail("No VirtualFolderItemFactory should be found for factory name.");
        } catch (ClientException e) {
            assertEquals(
                    "No factory named nonExistentFactory. Please check the contributions to the following extension point: <extension target=\"org.nuxeo.drive.service.FileSystemItemAdapterService\" point=\"fileSystemItemFactory\">.",
                    e.getMessage());
        }
        try {
            fileSystemItemAdapterService.getVirtualFolderItemFactory("defaultFileSystemItemFactory");
            fail("No VirtualFolderItemFactory should be found for factory name.");
        } catch (ClientException e) {
            assertEquals(
                    "Factory class org.nuxeo.drive.service.impl.DefaultFileSystemItemFactory for factory defaultFileSystemItemFactory is not a VirtualFolderItemFactory.",
                    e.getMessage());
        }
        VirtualFolderItemFactory virtualFolderItemFactory = fileSystemItemAdapterService.getVirtualFolderItemFactory("dummyVirtualFolderItemFactory");
        assertNotNull(virtualFolderItemFactory);
        assertTrue(virtualFolderItemFactory.getClass().getName().endsWith(
                "DummyVirtualFolderItemFactory"));

        // -------------------------------------------------------------
        // Check #getActiveFileSystemItemFactories()
        // -------------------------------------------------------------
        Set<String> activeFactories = fileSystemItemAdapterService.getActiveFileSystemItemFactories();
        assertEquals(6, activeFactories.size());
        assertTrue(activeFactories.contains("defaultSyncRootFolderItemFactory"));
        assertTrue(activeFactories.contains("defaultFileSystemItemFactory"));
        assertTrue(activeFactories.contains("dummyDocTypeFactory"));
        assertTrue(activeFactories.contains("dummyFacetFactory"));
        assertTrue(activeFactories.contains("dummyVirtualFolderItemFactory"));
        assertTrue(activeFactories.contains("nullMergeTestFactory"));
    }

    @Test
    public void testContribOverride() throws Exception {

        harness.deployContrib("org.nuxeo.drive.core.test",
                "OSGI-INF/test-nuxeodrive-adapter-service-contrib-override.xml");
        Framework.getLocalService(ReloadService.class).reload();

        // Re-adapt the sync root to take the override into account
        syncRootItem = (FolderItem) fileSystemItemAdapterService.getFileSystemItem(syncRootFolder);
        syncRootItemId = syncRootItem.getId();

        // ------------------------------------------------------
        // Check file system item factory descriptors
        // ------------------------------------------------------
        Map<String, FileSystemItemFactoryDescriptor> fileSystemItemFactoryDescs = ((FileSystemItemAdapterServiceImpl) fileSystemItemAdapterService).getFileSystemItemFactoryDescriptors();
        assertNotNull(fileSystemItemFactoryDescs);
        assertEquals(11, fileSystemItemFactoryDescs.size());

        FileSystemItemFactoryDescriptor desc = fileSystemItemFactoryDescs.get("defaultSyncRootFolderItemFactory");
        assertNotNull(desc);
        assertEquals(10, desc.getOrder());
        assertEquals("defaultSyncRootFolderItemFactory", desc.getName());
        assertNull(desc.getDocType());
        assertEquals("DriveSynchronized", desc.getFacet());
        FileSystemItemFactory factory = desc.getFactory();
        assertTrue(factory instanceof DefaultSyncRootFolderItemFactory);

        desc = fileSystemItemFactoryDescs.get("defaultFileSystemItemFactory");
        assertNotNull(desc);
        assertEquals(50, desc.getOrder());
        assertEquals("defaultFileSystemItemFactory", desc.getName());
        assertNull(desc.getDocType());
        assertNull(desc.getFacet());
        factory = desc.getFactory();
        assertTrue(factory instanceof DefaultFileSystemItemFactory);

        desc = fileSystemItemFactoryDescs.get("dummyFacetFactory");
        assertNotNull(desc);
        assertEquals(20, desc.getOrder());
        assertEquals("dummyFacetFactory", desc.getName());
        assertNull(desc.getDocType());
        assertEquals("Folderish", desc.getFacet());
        factory = desc.getFactory();
        assertTrue(factory instanceof DefaultFileSystemItemFactory);

        desc = fileSystemItemFactoryDescs.get("dummyDocTypeFactory");
        assertNotNull(desc);
        assertEquals(30, desc.getOrder());
        assertEquals("dummyDocTypeFactory", desc.getName());
        assertEquals("File", desc.getDocType());
        assertNull(desc.getFacet());
        factory = desc.getFactory();
        assertTrue(factory instanceof DefaultFileSystemItemFactory);
        assertTrue(factory instanceof VersioningFileSystemItemFactory);
        assertEquals(
                60.0,
                ((VersioningFileSystemItemFactory) factory).getVersioningDelay(),
                .01);
        assertEquals(
                VersioningOption.MAJOR,
                ((VersioningFileSystemItemFactory) factory).getVersioningOption());

        desc = fileSystemItemFactoryDescs.get("dummyVirtualFolderItemFactory");
        assertNotNull(desc);

        desc = fileSystemItemFactoryDescs.get("nullMergeTestFactory");
        assertNotNull(desc);
        assertEquals(200, desc.getOrder());
        assertEquals("nullMergeTestFactory", desc.getName());
        assertEquals("Note", desc.getDocType());
        assertNull(desc.getFacet());
        factory = desc.getFactory();
        assertTrue(factory instanceof DummyFileItemFactory);

        // ------------------------------------------------------
        // Check ordered file system item factories
        // ------------------------------------------------------
        List<FileSystemItemFactoryWrapper> fileSystemItemFactories = ((FileSystemItemAdapterServiceImpl) fileSystemItemAdapterService).getFileSystemItemFactories();
        assertNotNull(fileSystemItemFactories);
        assertEquals(5, fileSystemItemFactories.size());

        FileSystemItemFactoryWrapper factoryWrapper = fileSystemItemFactories.get(0);
        assertNotNull(factoryWrapper);
        assertNull(factoryWrapper.getDocType());
        assertEquals("DriveSynchronized", factoryWrapper.getFacet());
        assertTrue(factoryWrapper.getFactory().getClass().getName().endsWith(
                "DefaultSyncRootFolderItemFactory"));

        factoryWrapper = fileSystemItemFactories.get(1);
        assertNotNull(factoryWrapper);
        assertNull(factoryWrapper.getDocType());
        assertEquals("Folderish", factoryWrapper.getFacet());
        assertTrue(factoryWrapper.getFactory().getClass().getName().endsWith(
                "DefaultFileSystemItemFactory"));

        factoryWrapper = fileSystemItemFactories.get(2);
        assertNotNull(factoryWrapper);
        assertEquals("File", factoryWrapper.getDocType());
        assertNull(factoryWrapper.getFacet());
        assertTrue(factoryWrapper.getFactory().getClass().getName().endsWith(
                "DefaultFileSystemItemFactory"));

        factoryWrapper = fileSystemItemFactories.get(3);
        assertNotNull(factoryWrapper);

        factoryWrapper = fileSystemItemFactories.get(4);
        assertNotNull(factoryWrapper);
        assertEquals("Note", factoryWrapper.getDocType());
        assertNull(factoryWrapper.getFacet());
        assertTrue(factoryWrapper.getFactory().getClass().getName().endsWith(
                "DummyFileItemFactory"));

        // -------------------------------------------------------------
        // Check #getFileSystemItem(DocumentModel doc)
        // -------------------------------------------------------------
        // File => should try the dummyDocTypeFactory bound to the
        // DefaultFileSystemItemFactory class, returning null because the
        // document has no file, then try the dummyVirtualFolderItemFactory
        // bound to the DummyVirtualFolderItemFactory, returning null because
        // virtual
        file.setPropertyValue("file:content", null);
        session.saveDocument(file);
        FileSystemItem fsItem = fileSystemItemAdapterService.getFileSystemItem(file);
        assertNull(fsItem);

        // Folder => should use the dummyFacetFactory bound to the
        // DefaultFileSystemItemFactory class
        fsItem = fileSystemItemAdapterService.getFileSystemItem(folder);
        assertNotNull(fsItem);
        assertTrue(fsItem instanceof FolderItem);
        assertTrue(((FolderItem) fsItem).getCanCreateChild());
        assertEquals("dummyFacetFactory#test#" + folder.getId(), fsItem.getId());
        assertEquals(syncRootItemId, fsItem.getParentId());
        assertEquals("Jack's folder", fsItem.getName());
        assertTrue(fsItem.isFolder());
        assertEquals("Jack", fsItem.getCreator());

        // Custom => should try the dummyVirtualFolderItemFactory
        // bound to the DummyVirtualFolderItemFactory, returning null because
        // virtual
        fsItem = fileSystemItemAdapterService.getFileSystemItem(custom);
        assertNull(fsItem);

        // -------------------------------------------------------------
        // Check #getFileSystemItem(DocumentModel doc, String parentId)
        // -------------------------------------------------------------
        // Folder => should use the dummyFacetFactory bound to the
        // DefaultFileSystemItemFactory class
        fsItem = fileSystemItemAdapterService.getFileSystemItem(folder,
                syncRootItem);
        assertNotNull(fsItem);
        assertEquals(syncRootItemId, fsItem.getParentId());

        // -------------------------------------------------------------
        // Check #getFileSystemItemFactoryForId(String id)
        // -------------------------------------------------------------
        // Disabled default factory
        String fsItemId = "defaultFileSystemItemFactory#test#someId";
        try {
            fileSystemItemAdapterService.getFileSystemItemFactoryForId(fsItemId);
            fail("No fileSystemItemFactory should be found for FileSystemItem id.");
        } catch (ClientException e) {
            assertEquals(
                    "No fileSystemItemFactory found for FileSystemItem with id defaultFileSystemItemFactory#test#someId. Please check the contributions to the following extension point: <extension target=\"org.nuxeo.drive.service.FileSystemItemAdapterService\" point=\"fileSystemItemFactory\"> and make sure there is at least one defining a FileSystemItemFactory class for which the #canHandleFileSystemItemId(String id) method returns true.",
                    e.getMessage());
        }

        // Factory with #canHandleFileSystemItemId returning true
        fsItemId = "dummyDocTypeFactory#test#someId";
        FileSystemItemFactory fsItemFactory = fileSystemItemAdapterService.getFileSystemItemFactoryForId(fsItemId);
        assertNotNull(fsItemFactory);
        assertEquals("dummyDocTypeFactory", fsItemFactory.getName());
        assertTrue(fsItemFactory.getClass().getName().endsWith(
                "DefaultFileSystemItemFactory"));
        assertTrue(fsItemFactory.canHandleFileSystemItemId(fsItemId));

        // Other test factory with #canHandleFileSystemItemId returning true
        fsItemId = "dummyFacetFactory#test#someId";
        fsItemFactory = fileSystemItemAdapterService.getFileSystemItemFactoryForId(fsItemId);
        assertNotNull(fsItemFactory);
        assertEquals("dummyFacetFactory", fsItemFactory.getName());
        assertTrue(fsItemFactory.getClass().getName().endsWith(
                "DefaultFileSystemItemFactory"));
        assertTrue(fsItemFactory.canHandleFileSystemItemId(fsItemId));

        // Top level folder item factory
        fsItemId = "org.nuxeo.drive.service.adapter.DummyTopLevelFolderItemFactory#";
        fsItemFactory = fileSystemItemAdapterService.getFileSystemItemFactoryForId(fsItemId);
        assertNotNull(fsItemFactory);
        assertTrue(fsItemFactory.getName().endsWith(
                "DummyTopLevelFolderItemFactory"));
        assertTrue(fsItemFactory.getClass().getName().endsWith(
                "DummyTopLevelFolderItemFactory"));
        assertTrue(fsItemFactory.canHandleFileSystemItemId(fsItemId));

        // -------------------------------------------------------------
        // Check #getTopLevelFolderItemFactory()
        // -------------------------------------------------------------
        TopLevelFolderItemFactory topLevelFactory = fileSystemItemAdapterService.getTopLevelFolderItemFactory();
        assertNotNull(topLevelFactory);
        assertTrue(topLevelFactory.getClass().getName().endsWith(
                "DummyTopLevelFolderItemFactory"));
        assertTrue(topLevelFactory instanceof DummyTopLevelFolderItemFactory);

        // -------------------------------------------------------------
        // Check #getActiveFileSystemItemFactories()
        // -------------------------------------------------------------
        Set<String> activeFactories = fileSystemItemAdapterService.getActiveFileSystemItemFactories();
        assertEquals(5, activeFactories.size());
        assertTrue(activeFactories.contains("defaultSyncRootFolderItemFactory"));
        assertTrue(activeFactories.contains("dummyDocTypeFactory"));
        assertTrue(activeFactories.contains("dummyFacetFactory"));
        assertTrue(activeFactories.contains("dummyVirtualFolderItemFactory"));
        assertTrue(activeFactories.contains("nullMergeTestFactory"));

        harness.undeployContrib("org.nuxeo.drive.core.test",
                "OSGI-INF/test-nuxeodrive-adapter-service-contrib-override.xml");
        Framework.getLocalService(ReloadService.class).reload();
    }

}
