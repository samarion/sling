/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sling.ide.eclipse.ui.internal;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import org.apache.sling.ide.eclipse.core.ISlingLaunchpadServer;
import org.apache.sling.ide.eclipse.core.ProjectUtil;
import org.apache.sling.ide.eclipse.core.ResourceUtil;
import org.apache.sling.ide.eclipse.core.ServerUtil;
import org.apache.sling.ide.filter.Filter;
import org.apache.sling.ide.filter.FilterLocator;
import org.apache.sling.ide.filter.FilterResult;
import org.apache.sling.ide.serialization.SerializationData;
import org.apache.sling.ide.serialization.SerializationDataBuilder;
import org.apache.sling.ide.serialization.SerializationException;
import org.apache.sling.ide.serialization.SerializationKind;
import org.apache.sling.ide.serialization.SerializationKindManager;
import org.apache.sling.ide.serialization.SerializationManager;
import org.apache.sling.ide.transport.Command;
import org.apache.sling.ide.transport.Repository;
import org.apache.sling.ide.transport.RepositoryException;
import org.apache.sling.ide.transport.ResourceProxy;
import org.apache.sling.ide.transport.Result;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.wst.server.core.IServer;

// intentionally does not implement IRunnableWithProgress to cut dependency on JFace
public class ImportRepositoryContentAction {

    private final IServer server;
    private final IFile filterFile;
    private final IPath projectRelativePath;
    private final IProject project;
    private SerializationManager serializationManager;
	private SerializationDataBuilder builder;

    /**
     * @param server
     * @param filterFile
     * @param projectRelativePath
     * @param project
     * @throws SerializationException 
     */
    public ImportRepositoryContentAction(IServer server, IFile filterFile, IPath projectRelativePath,
            IProject project, SerializationManager serializationManager) throws SerializationException {
        this.server = server;
        this.filterFile = filterFile;
        this.projectRelativePath = projectRelativePath;
        this.project = project;
        this.serializationManager = serializationManager;
    }

    public void run(IProgressMonitor monitor) throws InvocationTargetException, InterruptedException, SerializationException {
        Repository repository;
        try {
            repository = ServerUtil.getRepository(server, monitor);
        } catch (CoreException e) {
            throw new InvocationTargetException(e);
        }

        this.builder = serializationManager.newBuilder(
        		repository, ProjectUtil.getSyncDirectoryFile(project));

        monitor.setTaskName("Loading configuration...");
        ISlingLaunchpadServer launchpad = (ISlingLaunchpadServer) server.loadAdapter(
                ISlingLaunchpadServer.class, monitor);

        int oldPublishState = launchpad.getPublishState();
        // TODO disabling publish does not work; since the publish is done async
        // Not sure if there is a simple workaround. Anyway, the only side effect is that we
        // make too many calls after the import, functionality is not affected
        if (server.canPublish().isOK() && oldPublishState != ISlingLaunchpadServer.PUBLISH_STATE_NEVER) {
            launchpad.setPublishState(ISlingLaunchpadServer.PUBLISH_STATE_NEVER, monitor);
        }

        SerializationKindManager skm;
        
        try {
            skm = new SerializationKindManager();
            skm.init(repository);
        } catch (RepositoryException e1) {
            throw new InvocationTargetException(e1);
        }

        Filter filter = null;

        FilterLocator filterLocator = Activator.getDefault().getFilterLocator();
        InputStream contents = null;

        if (filterFile != null && filterFile.exists()) {
            try {
                contents = filterFile.getContents();
                filter = filterLocator.loadFilter(contents);
            } catch (CoreException e) {
                throw new InvocationTargetException(e);
            } catch (IOException e) {
                throw new InvocationTargetException(e);
            } finally {
                if (contents != null) {
                    try {
                        contents.close();
                    } catch (IOException e) {
                        // don't care
                    }
                }
            }
        }

        monitor.worked(5);

        try {

            // TODO: We should try to make this give 'nice' progress feedback (aka here's what I'm
            // processing)
            monitor.setTaskName("Importing...");
            monitor.worked(10);

            crawlChildrenAndImport(repository, filter, "/", project, projectRelativePath);

            monitor.setTaskName("Import Complete");
            monitor.worked(100);
        } catch (Exception e) {
            throw new InvocationTargetException(e);
        } finally {
            if (oldPublishState != ISlingLaunchpadServer.PUBLISH_STATE_NEVER) {
                launchpad.setPublishState(oldPublishState, monitor);
            }
            if (builder!=null) {
            	builder.destroy();
            	builder = null;
            }
            monitor.done();
        }

    }

    /**
     * Crawls the repository and recursively imports founds resources
     * 
     * @param repository the sling repository to import from
     * @param filter
     * @param path the current path to import from
     * @param project the project to create resources in
     * @param projectRelativePath the path, relative to the project root, where the resources should be
     *            created
     * @param tracer
     * @throws JSONException
     * @throws RepositoryException
     * @throws CoreException
     * @throws IOException
     */
    // TODO: This probably should be pushed into the service layer
    private void crawlChildrenAndImport(Repository repository, Filter filter, String path,
            IProject project, IPath projectRelativePath)
            throws RepositoryException, CoreException, IOException, SerializationException {

        File contentSyncRoot = ProjectUtil.getSyncDirectoryFullPath(project).toFile();

        System.out.println("crawlChildrenAndImport(" + repository + ", " + path + ", " + project + ", "
                + projectRelativePath + ")");

        ResourceProxy resource = executeCommand(repository.newListChildrenNodeCommand(path));

        // TODO we should know all node types for which to create files and folders
        SerializationData serializationData = builder.buildSerializationData(contentSyncRoot, resource);
        System.out.println("For resource at path " + resource.getPath() + " got serialization data "
                + serializationData);

        final List<ResourceProxy> resourceChildren = new LinkedList<ResourceProxy>(resource.getChildren());
		if (serializationData != null) {
	
	        IPath fileOrFolderPath = projectRelativePath.append(serializationData.getFileOrFolderNameHint());
	
	        switch (serializationData.getSerializationKind()) {
	            case FILE: {
	                byte[] contents = executeCommand(repository.newGetNodeCommand(path));
	                importFile(project, fileOrFolderPath, contents);
	
	                if (serializationData.hasContents()) {
	                    // TODO - should we abstract out .dir serialization?
	                    IPath directoryPath = fileOrFolderPath.addFileExtension("dir");
	                    createFolder(project, directoryPath);
	                    createFile(project, directoryPath.append(serializationData.getNameHint()),
	                            serializationData.getContents());
	                    
	                    // filter out the child of type Repository.NT_RESOURCE
	                    for (Iterator<ResourceProxy> it = resourceChildren.iterator(); it
								.hasNext();) {
	                    	ResourceProxy child = it.next();
	                        if (Repository.NT_RESOURCE.equals(child.getProperties().get(Repository.JCR_PRIMARY_TYPE))) {
	                        	it.remove();
	                        	break;
	                        }
						}
	                }
	                break;
	            }
	            case FOLDER:
	            case METADATA_PARTIAL: {
	                createFolder(project, fileOrFolderPath);
	                if (serializationData.hasContents()) {
	                    createFile(project, fileOrFolderPath.append(serializationData.getNameHint()),
	                            serializationData.getContents());
	                }
	                break;
	            }
	
	            case METADATA_FULL: {
	                if (serializationData.hasContents()) {
	                    createFile(project, fileOrFolderPath, serializationData.getContents());
	                }
	                break;
	            }
	        }
	
	        System.out.println("Children: " + resourceChildren);
	
	        if (serializationData.getSerializationKind() == SerializationKind.METADATA_FULL) {
	            return;
	        }
        }

        for (ResourceProxy child : resourceChildren) {

            if (filter != null) {
                FilterResult filterResult = filter.filter(contentSyncRoot, child.getPath(),
                        repository.getRepositoryInfo());
                if (filterResult == FilterResult.DENY) {
                    continue;
                }
            }

            crawlChildrenAndImport(repository, filter, child.getPath(), project, projectRelativePath);
        }
    }

    private <T> T executeCommand(Command<T> command) throws RepositoryException {

        Result<T> result = command.execute();
        return result.get();
    }

    private void importFile(IProject project, IPath destinationPath, byte[] content)
            throws CoreException {

        createFile(project, destinationPath, content);
    }

    private void createFolder(IProject project, IPath destinationPath) throws CoreException {

        IFolder destinationFolder = project.getFolder(destinationPath);
        if (destinationFolder.exists())
            return;

        destinationFolder.create(true, true, null /* TODO progress monitor */);

        destinationFolder.setSessionProperty(ResourceUtil.QN_IGNORE_NEXT_CHANGE, Boolean.TRUE.toString());
    }

    private void createFile(IProject project, IPath path, byte[] node) throws CoreException {

        IFile destinationFile = project.getFile(path);

        System.out.println("Writing content file at " + path);

        if (destinationFile.exists()) {
            /* TODO progress monitor */
            destinationFile.setContents(new ByteArrayInputStream(node), IResource.KEEP_HISTORY, null);
        } else {
            /* TODO progress monitor */
        	if (!destinationFile.getParent().exists()) {
        		createParents(destinationFile.getParent());
        	}
        	destinationFile.create(new ByteArrayInputStream(node), true, null);
        }

        destinationFile.setSessionProperty(ResourceUtil.QN_IGNORE_NEXT_CHANGE, Boolean.TRUE.toString());
    }
    
    private void createParents(IContainer container) throws CoreException {
    	if (container.exists()) {
    		return;
    	}
    	if (!(container instanceof IFolder)) {
    		return;
    	}
    	createParents(container.getParent());
    	IFolder parentFolder = (IFolder)container;
    	parentFolder.create(true, true, null);
    }

}