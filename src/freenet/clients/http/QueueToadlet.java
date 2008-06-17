/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URI;
import java.text.NumberFormat;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import com.db4o.ObjectContainer;

import freenet.client.DefaultMIMETypes;
import freenet.client.FetchResult;
import freenet.client.HighLevelSimpleClient;
import freenet.client.MetadataUnresolvedException;
import freenet.client.async.ClientContext;
import freenet.client.async.DBJob;
import freenet.keys.FreenetURI;
import freenet.l10n.L10n;
import freenet.node.NodeClientCore;
import freenet.node.RequestStarter;
import freenet.node.fcp.ClientGet;
import freenet.node.fcp.ClientPut;
import freenet.node.fcp.ClientPutDir;
import freenet.node.fcp.ClientPutMessage;
import freenet.node.fcp.ClientRequest;
import freenet.node.fcp.FCPServer;
import freenet.node.fcp.IdentifierCollisionException;
import freenet.node.fcp.MessageInvalidException;
import freenet.node.fcp.NotAllowedException;
import freenet.node.fcp.RequestCompletionCallback;
import freenet.node.useralerts.SimpleHTMLUserAlert;
import freenet.node.useralerts.UserAlert;
import freenet.support.HTMLNode;
import freenet.support.Logger;
import freenet.support.MultiValueTable;
import freenet.support.SizeUtil;
import freenet.support.api.Bucket;
import freenet.support.api.HTTPRequest;
import freenet.support.api.HTTPUploadedFile;
import freenet.support.io.BucketTools;
import freenet.support.io.Closer;
import freenet.support.io.FileBucket;
import freenet.support.io.NativeThread;

public class QueueToadlet extends Toadlet implements RequestCompletionCallback {

	private static final int LIST_IDENTIFIER = 1;
	private static final int LIST_SIZE = 2;
	private static final int LIST_MIME_TYPE = 3;
	private static final int LIST_DOWNLOAD = 4;
	private static final int LIST_PERSISTENCE = 5;
	private static final int LIST_KEY = 6;
	private static final int LIST_FILENAME = 7;
	private static final int LIST_PRIORITY = 8;
	private static final int LIST_FILES = 9;
	private static final int LIST_TOTAL_SIZE = 10;
	private static final int LIST_PROGRESS = 11;
	private static final int LIST_REASON = 12;

	private static final int MAX_IDENTIFIER_LENGTH = 1024*1024;
	private static final int MAX_FILENAME_LENGTH = 1024*1024;
	private static final int MAX_TYPE_LENGTH = 1024;
	static final int MAX_KEY_LENGTH = 1024*1024;
	
	private NodeClientCore core;
	final FCPServer fcp;
	
	private boolean isReversed = false;
	
	public QueueToadlet(NodeClientCore core, FCPServer fcp, HighLevelSimpleClient client) {
		super(client);
		this.core = core;
		this.fcp = fcp;
		if(fcp == null) throw new NullPointerException();
		fcp.setCompletionCallback(this);
		loadCompletedIdentifiers();
	}
	
	public void handlePost(URI uri, HTTPRequest request, ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException {
		
		if(!core.hasLoadedQueue()) {
			writeError(L10n.getString("QueueToadlet.notLoadedYetTitle"), L10n.getString("QueueToadlet.notLoadedYet"), ctx, false);
			return;
		}
		
		boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
		
		try {
			// Browse... button
			if (request.getPartAsString("insert-local", 128).length() > 0) {
				
				// Preserve the key
				
				FreenetURI insertURI;
				String keyType = request.getPartAsString("keytype", 3);
				if ("chk".equals(keyType)) {
					insertURI = new FreenetURI("CHK@");
				} else {
					try {
						String u = request.getPartAsString("key", 128);
						insertURI = new FreenetURI(u);
						if(logMINOR)
							Logger.minor(this, "Inserting key: "+insertURI+" ("+u+")");
					} catch (MalformedURLException mue1) {
						writeError(L10n.getString("QueueToadlet.errorInvalidURI"), L10n.getString("QueueToadlet.errorInvalidURIToU"), ctx);
						return;
					}
				}
				
				MultiValueTable responseHeaders = new MultiValueTable();
				responseHeaders.put("Location", "/files/?key="+insertURI.toACIIString());
				ctx.sendReplyHeaders(302, "Found", responseHeaders, null, 0);
				return;
			}			
			
			String pass = request.getPartAsString("formPassword", 32);
			if ((pass.length() == 0) || !pass.equals(core.formPassword)) {
				MultiValueTable headers = new MultiValueTable();
				headers.put("Location", "/queue/");
				ctx.sendReplyHeaders(302, "Found", headers, null, 0);
				if(logMINOR) Logger.minor(this, "No formPassword: "+pass);
				return;
			}

			if(request.isPartSet("remove_request") && (request.getPartAsString("remove_request", 32).length() > 0)) {
				String identifier = request.getPartAsString("identifier", MAX_IDENTIFIER_LENGTH);
				if(logMINOR) Logger.minor(this, "Removing "+identifier);
				try {
					fcp.removeGlobalRequestBlocking(identifier);
				} catch (MessageInvalidException e) {
					this.sendErrorPage(ctx, 200, 
							L10n.getString("QueueToadlet.failedToRemoveRequest"),
							L10n.getString("QueueToadlet.failedToRemove",
									new String[]{ "id", "message" },
									new String[]{ identifier, e.getMessage()}
							));
					return;
				}
				writePermanentRedirect(ctx, "Done", "/queue/");
				return;
			} else if(request.isPartSet("restart_request") && (request.getPartAsString("restart_request", 32).length() > 0)) {
				String identifier = request.getPartAsString("identifier", MAX_IDENTIFIER_LENGTH);
				if(logMINOR) Logger.minor(this, "Restarting "+identifier);
				fcp.restartBlocking(identifier);
				writePermanentRedirect(ctx, "Done", "/queue/");
				return;
			} else if(request.isPartSet("remove_AllRequests") && (request.getPartAsString("remove_AllRequests", 32).length() > 0)) {
				
				// FIXME panic button should just dump the entire database ???
				// FIXME what about non-global requests ???
				
				boolean success = fcp.removeAllGlobalRequestsBlocking();
				
				if(!success)
					this.sendErrorPage(ctx, 200, 
							L10n.getString("QueueToadlet.failedToRemoveRequest"),
							L10n.getString("QueueToadlet.failedToRemoveAll"));
				else
					writePermanentRedirect(ctx, "Done", "/queue/");
				return;
			}else if(request.isPartSet("download")) {
				// Queue a download
				if(!request.isPartSet("key")) {
					writeError(L10n.getString("QueueToadlet.errorNoKey"), L10n.getString("QueueToadlet.errorNoKeyToD"), ctx);
					return;
				}
				String expectedMIMEType = null;
				if(request.isPartSet("type")) {
					expectedMIMEType = request.getPartAsString("type", MAX_TYPE_LENGTH);
				}
				FreenetURI fetchURI;
				try {
					fetchURI = new FreenetURI(request.getPartAsString("key", MAX_KEY_LENGTH));
				} catch (MalformedURLException e) {
					writeError(L10n.getString("QueueToadlet.errorInvalidURI"), L10n.getString("QueueToadlet.errorInvalidURIToD"), ctx);
					return;
				}
				String persistence = request.getPartAsString("persistence", 32);
				String returnType = request.getPartAsString("return-type", 32);
				try {
					fcp.makePersistentGlobalRequestBlocking(fetchURI, expectedMIMEType, persistence, returnType);
				} catch (NotAllowedException e) {
					this.writeError(L10n.getString("QueueToadlet.errorDToDisk"), L10n.getString("QueueToadlet.errorDToDiskConfig"), ctx);
					return;
				}
				writePermanentRedirect(ctx, "Done", "/queue/");
				return;
			} else if (request.isPartSet("change_priority")) {
				String identifier = request.getPartAsString("identifier", MAX_IDENTIFIER_LENGTH);
				short newPriority = Short.parseShort(request.getPartAsString("priority", 32));
				fcp.modifyGlobalRequestBlocking(identifier, null, newPriority);
				writePermanentRedirect(ctx, "Done", "/queue/");
				return;
			} else if (request.getPartAsString("insert", 128).length() > 0) {
				FreenetURI insertURI;
				String keyType = request.getPartAsString("keytype", 3);
				if ("chk".equals(keyType)) {
					insertURI = new FreenetURI("CHK@");
				} else {
					try {
						String u = request.getPartAsString("key", 128);
						insertURI = new FreenetURI(u);
						if(logMINOR)
							Logger.minor(this, "Inserting key: "+insertURI+" ("+u+")");
					} catch (MalformedURLException mue1) {
						writeError(L10n.getString("QueueToadlet.errorInvalidURI"), L10n.getString("QueueToadlet.errorInvalidURIToU"), ctx);
						return;
					}
				}
				HTTPUploadedFile file = request.getUploadedFile("filename");
				if (file == null || file.getFilename().trim().length() == 0) {
					writeError(L10n.getString("QueueToadlet.errorNoFileSelected"), L10n.getString("QueueToadlet.errorNoFileSelectedU"), ctx);
					return;
				}
				boolean compress = request.getPartAsString("compress", 128).length() > 0;
				String identifier = file.getFilename() + "-fred-" + System.currentTimeMillis();
				String fnam;
				if(insertURI.getKeyType().equals("CHK"))
					fnam = file.getFilename();
				else
					fnam = null;
				/* copy bucket data */
				Bucket copiedBucket = core.persistentEncryptedTempBucketFactory.makeBucket(file.getData().size());
				BucketTools.copy(file.getData(), copiedBucket);
				final ClientPut clientPut;
				try {
					clientPut = new ClientPut(fcp.getGlobalForeverClient(), insertURI, identifier, Integer.MAX_VALUE, RequestStarter.BULK_SPLITFILE_PRIORITY_CLASS, ClientRequest.PERSIST_FOREVER, null, false, !compress, -1, ClientPutMessage.UPLOAD_FROM_DIRECT, null, file.getContentType(), copiedBucket, null, fnam, false, fcp);
				} catch (IdentifierCollisionException e) {
					Logger.error(this, "Cannot put same file twice in same millisecond");
					writePermanentRedirect(ctx, "Done", "/queue/");
					return;
				} catch (NotAllowedException e) {
					this.writeError(L10n.getString("QueueToadlet.errorAccessDenied"), L10n.getString("QueueToadlet.errorAccessDeniedFile", new String[]{ "file" }, new String[]{ file.getFilename() }), ctx);
					return;
				} catch (FileNotFoundException e) {
					this.writeError(L10n.getString("QueueToadlet.errorNoFileOrCannotRead"), L10n.getString("QueueToadlet.errorAccessDeniedFile", new String[]{ "file" }, new String[]{ file.getFilename() }), ctx);
					return;
				} catch (MalformedURLException mue1) {
					writeError(L10n.getString("QueueToadlet.errorInvalidURI"), L10n.getString("QueueToadlet.errorInvalidURIToU"), ctx);
					return;
				} catch (MetadataUnresolvedException e) {
					Logger.error(this, "Unresolved metadata in starting insert from data uploaded from browser: "+e, e);
					writePermanentRedirect(ctx, "Done", "/queue/");
					return;
					// FIXME should this be a proper localised message? It shouldn't happen... but we'd like to get reports if it does.
				}
				if(clientPut != null)
					fcp.startBlocking(clientPut);
				writePermanentRedirect(ctx, "Done", "/queue/");
				return;
			} else if (request.isPartSet("insert-local-file")) {
				String filename = request.getPartAsString("filename", MAX_FILENAME_LENGTH);
				if(logMINOR) Logger.minor(this, "Inserting local file: "+filename);
				File file = new File(filename);
				String identifier = file.getName() + "-fred-" + System.currentTimeMillis();
				String contentType = DefaultMIMETypes.guessMIMEType(filename, false);
				FreenetURI furi = new FreenetURI("CHK@");
				String key = request.getPartAsString("key", 128);
				if(key != null) {
					try {
						furi = new FreenetURI(key);
					} catch (MalformedURLException e) {
						writeError(L10n.getString("QueueToadlet.errorInvalidURI"), L10n.getString("QueueToadlet.errorInvalidURIToU"), ctx);
						return;
					}
				}
				String target = file.getName();
				if(!furi.getKeyType().equals("CHK"))
					target = null;
				final ClientPut clientPut;
				try {
					clientPut = new ClientPut(fcp.getGlobalForeverClient(), furi, identifier, Integer.MAX_VALUE, RequestStarter.BULK_SPLITFILE_PRIORITY_CLASS, ClientRequest.PERSIST_FOREVER, null, false, false, -1, ClientPutMessage.UPLOAD_FROM_DISK, file, contentType, new FileBucket(file, true, false, false, false, false), null, target, false, fcp);
					if(logMINOR) Logger.minor(this, "Started global request to insert "+file+" to CHK@ as "+identifier);
				} catch (IdentifierCollisionException e) {
					Logger.error(this, "Cannot put same file twice in same millisecond");
					writePermanentRedirect(ctx, "Done", "/queue/");
					return;
				} catch (MalformedURLException e) {
					writeError(L10n.getString("QueueToadlet.errorInvalidURI"), L10n.getString("QueueToadlet.errorInvalidURIToU"), ctx);
					return;
				} catch (FileNotFoundException e) {
					this.writeError(L10n.getString("QueueToadlet.errorNoFileOrCannotRead"), L10n.getString("QueueToadlet.errorAccessDeniedFile", new String[]{ "file" }, new String[]{ target }), ctx);
					return;
				} catch (NotAllowedException e) {
					this.writeError(L10n.getString("QueueToadlet.errorAccessDenied"), L10n.getString("QueueToadlet.errorAccessDeniedFile", new String[]{ "file" }, new String[]{ file.getName() }), ctx);
					return;
				} catch (MetadataUnresolvedException e) {
					Logger.error(this, "Unresolved metadata in starting insert from data from file: "+e, e);
					writePermanentRedirect(ctx, "Done", "/queue/");
					return;
					// FIXME should this be a proper localised message? It shouldn't happen... but we'd like to get reports if it does.
				}
				fcp.startBlocking(clientPut);
				writePermanentRedirect(ctx, "Done", "/queue/");
				return;
			} else if (request.isPartSet("insert-local-dir")) {
				String filename = request.getPartAsString("filename", MAX_FILENAME_LENGTH);
				if(logMINOR) Logger.minor(this, "Inserting local directory: "+filename);
				File file = new File(filename);
				String identifier = file.getName() + "-fred-" + System.currentTimeMillis();
				FreenetURI furi = new FreenetURI("CHK@");
				String key = request.getPartAsString("key", 128);
				if(key != null) {
					try {
						furi = new FreenetURI(key);
					} catch (MalformedURLException e) {
						writeError(L10n.getString("QueueToadlet.errorInvalidURI"), L10n.getString("QueueToadlet.errorInvalidURIToU"), ctx);
						return;
					}
				}
				ClientPutDir clientPutDir;
				try {
					clientPutDir = new ClientPutDir(fcp.getGlobalForeverClient(), furi, identifier, Integer.MAX_VALUE, RequestStarter.BULK_SPLITFILE_PRIORITY_CLASS, ClientRequest.PERSIST_FOREVER, null, false, false, -1, file, null, false, true, false, fcp);
					if(logMINOR) Logger.minor(this, "Started global request to insert dir "+file+" to "+furi+" as "+identifier);
				} catch (IdentifierCollisionException e) {
					Logger.error(this, "Cannot put same directory twice in same millisecond");
					writePermanentRedirect(ctx, "Done", "/queue/");
					return;
				} catch (MalformedURLException e) {
					writeError(L10n.getString("QueueToadlet.errorInvalidURI"), L10n.getString("QueueToadlet.errorInvalidURIToU"), ctx);
					return;
				} catch (FileNotFoundException e) {
					this.writeError(L10n.getString("QueueToadlet.errorNoFileOrCannotRead"), L10n.getString("QueueToadlet.errorAccessDeniedFile", new String[]{ "file" }, new String[]{ file.toString() }), ctx);
					return;
				}
				fcp.startBlocking(clientPutDir);
				writePermanentRedirect(ctx, "Done", "/queue/");
				return;
			}
		} finally {
			request.freeParts();
		}
		this.handleGet(uri, new HTTPRequestImpl(uri), ctx);
	}
	
	private void writeError(String header, String message, ToadletContext context) throws ToadletContextClosedException, IOException {
		writeError(header, message, context, true);
	}
	
	private void writeError(String header, String message, ToadletContext context, boolean returnToQueuePage) throws ToadletContextClosedException, IOException {
		PageMaker pageMaker = context.getPageMaker();
		HTMLNode pageNode = pageMaker.getPageNode(header, context);
		HTMLNode contentNode = pageMaker.getContentNode(pageNode);
		if(context.isAllowedFullAccess())
			contentNode.addChild(core.alerts.createSummary());
		HTMLNode infobox = contentNode.addChild(pageMaker.getInfobox("infobox-error", header));
		HTMLNode infoboxContent = pageMaker.getContentNode(infobox);
		infoboxContent.addChild("#", message);
		if(returnToQueuePage)
			infoboxContent.addChild("div").addChildren(new HTMLNode[] { new HTMLNode("#", "Return to "), new HTMLNode("a", "href", "/queue/", "queue page"), new HTMLNode("#", ".") });
		writeHTMLReply(context, 400, "Bad request", pageNode.generate());
	}

	public void handleGet(URI uri, final HTTPRequest request, ToadletContext ctx) 
	throws ToadletContextClosedException, IOException, RedirectException {
		
		// We ensure that we have a FCP server running
		if(!fcp.enabled){
			writeError(L10n.getString("QueueToadlet.fcpIsMissing"), L10n.getString("QueueToadlet.pleaseEnableFCP"), ctx, false);
			return;
		}
		
		if(!core.hasLoadedQueue()) {
			writeError(L10n.getString("QueueToadlet.notLoadedYetTitle"), L10n.getString("QueueToadlet.notLoadedYet"), ctx, false);
			return;
		}
		
		final String requestPath = request.getPath().substring("/queue/".length());
		
		if (requestPath.length() > 0) {
			/* okay, there is something in the path, check it. */
			try {
				FreenetURI key = new FreenetURI(requestPath);
				
				/* locate request */
				FetchResult result = fcp.getCompletedRequestBlocking(key);
				if(result != null) {
					Bucket data = result.asBucket();
					String mimeType = result.getMimeType();
					String requestedMimeType = request.getParam("type", null);
					String forceString = request.getParam("force");
					FProxyToadlet.handleDownload(ctx, data, ctx.getBucketFactory(), mimeType, requestedMimeType, forceString, request.isParameterSet("forcedownload"), "/queue/", key, "", "/queue/", false, ctx);
				}
			} catch (MalformedURLException mue1) {
			}
			return;
		}
		
		PageMaker pageMaker = ctx.getPageMaker();
		
		// First, get the queued requests, and separate them into different types.
		LinkedList completedDownloadToDisk = new LinkedList();
		LinkedList completedDownloadToTemp = new LinkedList();
		LinkedList completedUpload = new LinkedList();
		LinkedList completedDirUpload = new LinkedList();
		
		LinkedList failedDownload = new LinkedList();
		LinkedList failedUpload = new LinkedList();
		LinkedList failedDirUpload = new LinkedList();
		
		LinkedList uncompletedDownload = new LinkedList();
		LinkedList uncompletedUpload = new LinkedList();
		LinkedList uncompletedDirUpload = new LinkedList();
		
		ClientRequest[] reqs = fcp.getGlobalRequests();
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Request count: "+reqs.length);
		
		if(reqs.length < 1){
			HTMLNode pageNode = pageMaker.getPageNode(L10n.getString("QueueToadlet.title", new String[]{ "nodeName" }, new String[]{ core.getMyName() }), ctx);
			HTMLNode contentNode = pageMaker.getContentNode(pageNode);
			/* add alert summary box */
			if(ctx.isAllowedFullAccess())
				contentNode.addChild(core.alerts.createSummary());
			HTMLNode infobox = contentNode.addChild(pageMaker.getInfobox("infobox-information", L10n.getString("QueueToadlet.globalQueueIsEmpty")));
			HTMLNode infoboxContent = pageMaker.getContentNode(infobox);
			infoboxContent.addChild("#", L10n.getString("QueueToadlet.noTaskOnGlobalQueue"));
			contentNode.addChild(createInsertBox(pageMaker, ctx, core.isAdvancedModeEnabled()));
			writeHTMLReply(ctx, 200, "OK", pageNode.generate());
			return;
		}

		short lowestQueuedPrio = RequestStarter.MINIMUM_PRIORITY_CLASS;
		
		for(int i=0;i<reqs.length;i++) {
			ClientRequest req = reqs[i];
			if(req instanceof ClientGet) {
				ClientGet cg = (ClientGet) req;
				if(cg.hasSucceeded()) {
					if(cg.isDirect())
						completedDownloadToTemp.add(cg);
					else if(cg.isToDisk())
						completedDownloadToDisk.add(cg);
					else
						// FIXME
						Logger.error(this, "Don't know what to do with "+cg);
				} else if(cg.hasFinished()) {
					failedDownload.add(cg);
				} else {
					short prio = cg.getPriority();
					if(prio < lowestQueuedPrio)
						lowestQueuedPrio = prio;
					uncompletedDownload.add(cg);
				}
			} else if(req instanceof ClientPut) {
				ClientPut cp = (ClientPut) req;
				if(cp.hasSucceeded()) {
					completedUpload.add(cp);
				} else if(cp.hasFinished()) {
					failedUpload.add(cp);
				} else {
					short prio = req.getPriority();
					if(prio < lowestQueuedPrio)
						lowestQueuedPrio = prio;
					uncompletedUpload.add(cp);
				}
			} else if(req instanceof ClientPutDir) {
				ClientPutDir cp = (ClientPutDir) req;
				if(cp.hasSucceeded()) {
					completedDirUpload.add(cp);
				} else if(cp.hasFinished()) {
					failedDirUpload.add(cp);
				} else {
					short prio = req.getPriority();
					if(prio < lowestQueuedPrio)
						lowestQueuedPrio = prio;
					uncompletedDirUpload.add(cp);
				}
			}
		}
		
		Comparator jobComparator = new Comparator() {
			public int compare(Object first, Object second) {
				ClientRequest firstRequest = (ClientRequest) first;
				ClientRequest secondRequest = (ClientRequest) second;

				int result = 0;
				boolean isSet = true;
				
				if(request.isParameterSet("sortBy")){
					final String sortBy = request.getParam("sortBy"); 

					if(sortBy.equals("id")){
						result = firstRequest.getIdentifier().compareToIgnoreCase(secondRequest.getIdentifier());
					}else if(sortBy.equals("size")){
						result = (firstRequest.getTotalBlocks() - secondRequest.getTotalBlocks()) < 0 ? -1 : 1;
					}else if(sortBy.equals("progress")){
						result = firstRequest.getSuccessFraction() - secondRequest.getSuccessFraction() < 0 ? -1 : 1;
					}else
						isSet=false;
				}else
					isSet=false;
				
				if(!isSet){
					int priorityDifference =  firstRequest.getPriority() - secondRequest.getPriority(); 
					if (priorityDifference != 0) 
						result = (priorityDifference < 0 ? -1 : 1);
					else
						result = firstRequest.getIdentifier().compareTo(secondRequest.getIdentifier());
				}

				if(result == 0){
					return 0;
				}else if(request.isParameterSet("reversed")){
					isReversed = true;
					return result > 0 ? -1 : 1;
				}else{
					isReversed = false;
					return result < 0 ? -1 : 1;
				}
			}
		};
		
		Collections.sort(completedDownloadToDisk, jobComparator);
		Collections.sort(completedDownloadToTemp, jobComparator);
		Collections.sort(completedUpload, jobComparator);
		Collections.sort(completedDirUpload, jobComparator);
		Collections.sort(failedDownload, jobComparator);
		Collections.sort(failedUpload, jobComparator);
		Collections.sort(failedDirUpload, jobComparator);
		Collections.sort(uncompletedDownload, jobComparator);
		Collections.sort(uncompletedUpload, jobComparator);
		Collections.sort(uncompletedDirUpload, jobComparator);
		
		HTMLNode pageNode = pageMaker.getPageNode("(" + (uncompletedDirUpload.size() + uncompletedDownload.size()
				+ uncompletedUpload.size()) + '/' + (failedDirUpload.size() + failedDownload.size() + failedUpload.size()) + '/'
                + (completedDirUpload.size() + completedDownloadToDisk.size() + completedDownloadToTemp.size()
				+ completedUpload.size()) + ") " +
				L10n.getString("QueueToadlet.title", new String[]{ "nodeName" }, new String[]{ core.getMyName() }), ctx);
		HTMLNode contentNode = pageMaker.getContentNode(pageNode);

		/* add alert summary box */
		if(ctx.isAllowedFullAccess())
			contentNode.addChild(core.alerts.createSummary());
		/* add file insert box */
		contentNode.addChild(createInsertBox(pageMaker, ctx, core.isAdvancedModeEnabled()));

		/* navigation bar */
		HTMLNode navigationBar = pageMaker.getInfobox("navbar", L10n.getString("QueueToadlet.requestNavigation"));
		HTMLNode navigationContent = pageMaker.getContentNode(navigationBar).addChild("ul");
		boolean includeNavigationBar = false;
		if (!completedDownloadToTemp.isEmpty()) {
			navigationContent.addChild("li").addChild("a", "href", "#completedDownloadToTemp", L10n.getString("QueueToadlet.completedDtoTemp", new String[]{ "size" }, new String[]{ String.valueOf(completedDownloadToTemp.size()) }));
			includeNavigationBar = true;
		}
		if (!completedDownloadToDisk.isEmpty()) {
			navigationContent.addChild("li").addChild("a", "href", "#completedDownloadToDisk", L10n.getString("QueueToadlet.completedDtoDisk", new String[]{ "size" }, new String[]{ String.valueOf(completedDownloadToDisk.size()) }));
			includeNavigationBar = true;
		}
		if (!completedUpload.isEmpty()) {
			navigationContent.addChild("li").addChild("a", "href", "#completedUpload", L10n.getString("QueueToadlet.completedU", new String[]{ "size" }, new String[]{ String.valueOf(completedUpload.size()) }));
			includeNavigationBar = true;
		}
		if (!completedDirUpload.isEmpty()) {
			navigationContent.addChild("li").addChild("a", "href", "#completedDirUpload", L10n.getString("QueueToadlet.completedDU", new String[]{ "size" }, new String[]{ String.valueOf(completedDirUpload.size()) }));
			includeNavigationBar = true;
		}
		if (!failedDownload.isEmpty()) {
			navigationContent.addChild("li").addChild("a", "href", "#failedDownload", L10n.getString("QueueToadlet.failedD", new String[]{ "size" }, new String[]{ String.valueOf(failedDownload.size()) }));
			includeNavigationBar = true;
		}
		if (!failedUpload.isEmpty()) {
			navigationContent.addChild("li").addChild("a", "href", "#failedUpload", L10n.getString("QueueToadlet.failedU", new String[]{ "size" }, new String[]{ String.valueOf(failedUpload.size()) }));
			includeNavigationBar = true;
		}
		if (!failedDirUpload.isEmpty()) {
			navigationContent.addChild("li").addChild("a", "href", "#failedDirUpload", L10n.getString("QueueToadlet.failedDU", new String[]{ "size" }, new String[]{ String.valueOf(failedDirUpload.size()) }));
			includeNavigationBar = true;
		}
		if (!uncompletedDownload.isEmpty()) {
			navigationContent.addChild("li").addChild("a", "href", "#uncompletedDownload", L10n.getString("QueueToadlet.DinProgress", new String[]{ "size" }, new String[]{ String.valueOf(uncompletedDownload.size()) }));
			includeNavigationBar = true;
		}
		if (!uncompletedUpload.isEmpty()) {
			navigationContent.addChild("li").addChild("a", "href", "#uncompletedUpload", L10n.getString("QueueToadlet.UinProgress", new String[]{ "size" }, new String[]{ String.valueOf(uncompletedUpload.size()) }));
			includeNavigationBar = true;
		}
		if (!uncompletedDirUpload.isEmpty()) {
			navigationContent.addChild("li").addChild("a", "href", "#uncompletedDirUpload", L10n.getString("QueueToadlet.DUinProgress", new String[]{ "size" }, new String[]{ String.valueOf(uncompletedDirUpload.size()) }));
			includeNavigationBar = true;
		}

		if (includeNavigationBar) {
			contentNode.addChild(navigationBar);
		}

		final String[] priorityClasses = new String[] { 
				L10n.getString("QueueToadlet.priority0"),
				L10n.getString("QueueToadlet.priority1"),
				L10n.getString("QueueToadlet.priority2"),
				L10n.getString("QueueToadlet.priority3"),
				L10n.getString("QueueToadlet.priority4"),
				L10n.getString("QueueToadlet.priority5"),
				L10n.getString("QueueToadlet.priority6")
		};

		boolean advancedModeEnabled = core.isAdvancedModeEnabled();

		HTMLNode legendBox = contentNode.addChild(pageMaker.getInfobox("legend", L10n.getString("QueueToadlet.legend")));
		HTMLNode legendContent = pageMaker.getContentNode(legendBox);
		HTMLNode legendTable = legendContent.addChild("table", "class", "queue");
		HTMLNode legendRow = legendTable.addChild("tr");
		for(int i=0; i<7; i++){
			if(i > RequestStarter.INTERACTIVE_PRIORITY_CLASS || advancedModeEnabled || i <= lowestQueuedPrio)
				legendRow.addChild("td", "class", "priority" + i, priorityClasses[i]);
		}

		if (reqs.length > 1 && SimpleToadletServer.isPanicButtonToBeShown) {
			contentNode.addChild(createPanicBox(pageMaker, ctx));
		}

		if (!completedDownloadToTemp.isEmpty()) {
			contentNode.addChild("a", "id", "completedDownloadToTemp");
			HTMLNode completedDownloadsTempInfobox = contentNode.addChild(pageMaker.getInfobox("completed_requests", L10n.getString("QueueToadlet.completedDinTempDirectory", new String[]{ "size" }, new String[]{ String.valueOf(completedDownloadToTemp.size()) })));
			HTMLNode completedDownloadsToTempContent = pageMaker.getContentNode(completedDownloadsTempInfobox);
			if (advancedModeEnabled) {
				completedDownloadsToTempContent.addChild(createRequestTable(pageMaker, ctx, completedDownloadToTemp, new int[] { LIST_IDENTIFIER, LIST_SIZE, LIST_MIME_TYPE, LIST_DOWNLOAD, LIST_PERSISTENCE, LIST_KEY }, priorityClasses, advancedModeEnabled, false));
			} else {
				completedDownloadsToTempContent.addChild(createRequestTable(pageMaker, ctx, completedDownloadToTemp, new int[] { LIST_SIZE, LIST_MIME_TYPE, LIST_DOWNLOAD, LIST_PERSISTENCE, LIST_KEY }, priorityClasses, advancedModeEnabled, false));
			}
		}
		
		if (!completedDownloadToDisk.isEmpty()) {
			contentNode.addChild("a", "id", "completedDownloadToDisk");
			HTMLNode completedToDiskInfobox = contentNode.addChild(pageMaker.getInfobox("completed_requests", L10n.getString("QueueToadlet.completedDinDownloadDirectory", new String[]{ "size" }, new String[]{ String.valueOf(completedDownloadToDisk.size()) })));
			HTMLNode completedToDiskInfoboxContent = pageMaker.getContentNode(completedToDiskInfobox);
			if (advancedModeEnabled) {
				completedToDiskInfoboxContent.addChild(createRequestTable(pageMaker, ctx, completedDownloadToDisk, new int[] { LIST_IDENTIFIER, LIST_FILENAME, LIST_SIZE, LIST_MIME_TYPE, LIST_DOWNLOAD, LIST_PERSISTENCE, LIST_KEY }, priorityClasses, advancedModeEnabled, false));
			} else {
				completedToDiskInfoboxContent.addChild(createRequestTable(pageMaker, ctx, completedDownloadToDisk, new int[] { LIST_FILENAME, LIST_SIZE, LIST_MIME_TYPE, LIST_DOWNLOAD, LIST_PERSISTENCE, LIST_KEY }, priorityClasses, advancedModeEnabled, false));
			}
		}

		if (!completedUpload.isEmpty()) {
			contentNode.addChild("a", "id", "completedUpload");
			HTMLNode completedUploadInfobox = contentNode.addChild(pageMaker.getInfobox("completed_requests", L10n.getString("QueueToadlet.completedU", new String[]{ "size" }, new String[]{ String.valueOf(completedUpload.size()) })));
			HTMLNode completedUploadInfoboxContent = pageMaker.getContentNode(completedUploadInfobox);
			if (advancedModeEnabled) {
				completedUploadInfoboxContent.addChild(createRequestTable(pageMaker, ctx, completedUpload, new int[] { LIST_IDENTIFIER, LIST_FILENAME, LIST_SIZE, LIST_MIME_TYPE, LIST_PERSISTENCE, LIST_KEY }, priorityClasses, advancedModeEnabled, true));
			} else  {
				completedUploadInfoboxContent.addChild(createRequestTable(pageMaker, ctx, completedUpload, new int[] { LIST_FILENAME, LIST_SIZE, LIST_MIME_TYPE, LIST_PERSISTENCE, LIST_KEY }, priorityClasses, advancedModeEnabled, true));
			}
		}
		
		if (!completedDirUpload.isEmpty()) {
			contentNode.addChild("a", "id", "completedDirUpload");
			HTMLNode completedUploadDirInfobox = contentNode.addChild(pageMaker.getInfobox("completed_requests", L10n.getString("QueueToadlet.completedUDirectory", new String[]{ "size" }, new String[]{ String.valueOf(completedDirUpload.size()) })));
			HTMLNode completedUploadDirContent = pageMaker.getContentNode(completedUploadDirInfobox);
			if (advancedModeEnabled) {
				completedUploadDirContent.addChild(createRequestTable(pageMaker, ctx, completedDirUpload, new int[] { LIST_IDENTIFIER, LIST_FILES, LIST_TOTAL_SIZE, LIST_PERSISTENCE, LIST_KEY }, priorityClasses, advancedModeEnabled, true));
			} else {
				completedUploadDirContent.addChild(createRequestTable(pageMaker, ctx, completedDirUpload, new int[] { LIST_FILES, LIST_TOTAL_SIZE, LIST_PERSISTENCE, LIST_KEY }, priorityClasses, advancedModeEnabled, true));
			}
		}
				
		if (!failedDownload.isEmpty()) {
			contentNode.addChild("a", "id", "failedDownload");
			HTMLNode failedInfobox = contentNode.addChild(pageMaker.getInfobox("failed_requests", L10n.getString("QueueToadlet.failedD", new String[]{ "size" }, new String[]{ String.valueOf(failedDownload.size()) })));
			HTMLNode failedContent = pageMaker.getContentNode(failedInfobox);
			if (advancedModeEnabled) {
				failedContent.addChild(createRequestTable(pageMaker, ctx, failedDownload, new int[] { LIST_IDENTIFIER, LIST_FILENAME, LIST_SIZE, LIST_MIME_TYPE, LIST_PROGRESS, LIST_REASON, LIST_PERSISTENCE, LIST_KEY }, priorityClasses, advancedModeEnabled, false));
			} else {
				failedContent.addChild(createRequestTable(pageMaker, ctx, failedDownload, new int[] { LIST_FILENAME, LIST_SIZE, LIST_MIME_TYPE, LIST_PROGRESS, LIST_REASON, LIST_PERSISTENCE, LIST_KEY }, priorityClasses, advancedModeEnabled, false));
			}
		}
		
		if (!failedUpload.isEmpty()) {
			contentNode.addChild("a", "id", "failedUpload");
			HTMLNode failedInfobox = contentNode.addChild(pageMaker.getInfobox("failed_requests", L10n.getString("QueueToadlet.failedU", new String[]{ "size" }, new String[]{ String.valueOf(failedUpload.size()) })));
			HTMLNode failedContent = pageMaker.getContentNode(failedInfobox);
			if (advancedModeEnabled) {
				failedContent.addChild(createRequestTable(pageMaker, ctx, failedUpload, new int[] { LIST_IDENTIFIER, LIST_FILENAME, LIST_SIZE, LIST_MIME_TYPE, LIST_PROGRESS, LIST_REASON, LIST_PERSISTENCE, LIST_KEY }, priorityClasses, advancedModeEnabled, true));
			} else {
				failedContent.addChild(createRequestTable(pageMaker, ctx, failedUpload, new int[] { LIST_FILENAME, LIST_SIZE, LIST_MIME_TYPE, LIST_PROGRESS, LIST_REASON, LIST_PERSISTENCE, LIST_KEY }, priorityClasses, advancedModeEnabled, true));
			}
		}
		
		if (!failedDirUpload.isEmpty()) {
			contentNode.addChild("a", "id", "failedDirUpload");
			HTMLNode failedInfobox = contentNode.addChild(pageMaker.getInfobox("failed_requests", L10n.getString("QueueToadlet.failedU", new String[]{ "size" }, new String[]{ String.valueOf(failedDirUpload.size()) })));
			HTMLNode failedContent = pageMaker.getContentNode(failedInfobox);
			if (advancedModeEnabled) {
				failedContent.addChild(createRequestTable(pageMaker, ctx, failedDirUpload, new int[] { LIST_IDENTIFIER, LIST_FILES, LIST_TOTAL_SIZE, LIST_PROGRESS, LIST_REASON, LIST_PERSISTENCE, LIST_KEY }, priorityClasses, advancedModeEnabled, true));
			} else {
				failedContent.addChild(createRequestTable(pageMaker, ctx, failedDirUpload, new int[] { LIST_FILES, LIST_TOTAL_SIZE, LIST_PROGRESS, LIST_REASON, LIST_PERSISTENCE, LIST_KEY }, priorityClasses, advancedModeEnabled, true));
			}
		}
		
		if (!uncompletedDownload.isEmpty()) {
			contentNode.addChild("a", "id", "uncompletedDownload");
			HTMLNode uncompletedInfobox = contentNode.addChild(pageMaker.getInfobox("requests_in_progress", L10n.getString("QueueToadlet.wipD", new String[]{ "size" }, new String[]{ String.valueOf(uncompletedDownload.size()) })));
			HTMLNode uncompletedContent = pageMaker.getContentNode(uncompletedInfobox);
			if (advancedModeEnabled) {
				uncompletedContent.addChild(createRequestTable(pageMaker, ctx, uncompletedDownload, new int[] { LIST_IDENTIFIER, LIST_PRIORITY, LIST_SIZE, LIST_MIME_TYPE, LIST_PROGRESS, LIST_PERSISTENCE, LIST_FILENAME, LIST_KEY }, priorityClasses, advancedModeEnabled, false));
			} else {
				uncompletedContent.addChild(createRequestTable(pageMaker, ctx, uncompletedDownload, new int[] { LIST_FILENAME, LIST_SIZE, LIST_MIME_TYPE, LIST_PROGRESS, LIST_PRIORITY, LIST_KEY, LIST_PERSISTENCE }, priorityClasses, advancedModeEnabled, false));
			}
		}
		
		if (!uncompletedUpload.isEmpty()) {
			contentNode.addChild("a", "id", "uncompletedUpload");
			HTMLNode uncompletedInfobox = contentNode.addChild(pageMaker.getInfobox("requests_in_progress", L10n.getString("QueueToadlet.wipU", new String[]{ "size" }, new String[]{ String.valueOf(uncompletedUpload.size()) })));
			HTMLNode uncompletedContent = pageMaker.getContentNode(uncompletedInfobox);
			if (advancedModeEnabled) {
				uncompletedContent.addChild(createRequestTable(pageMaker, ctx, uncompletedUpload, new int[] { LIST_IDENTIFIER, LIST_PRIORITY, LIST_SIZE, LIST_MIME_TYPE, LIST_PROGRESS, LIST_PERSISTENCE, LIST_FILENAME, LIST_KEY }, priorityClasses, advancedModeEnabled, true));
			} else {
				uncompletedContent.addChild(createRequestTable(pageMaker, ctx, uncompletedUpload, new int[] { LIST_FILENAME, LIST_SIZE, LIST_MIME_TYPE, LIST_PROGRESS, LIST_PRIORITY, LIST_KEY, LIST_PRIORITY, LIST_PERSISTENCE }, priorityClasses, advancedModeEnabled, true));
			}
		}
		
		if (!uncompletedDirUpload.isEmpty()) {
			contentNode.addChild("a", "id", "uncompletedDirUpload");
			HTMLNode uncompletedInfobox = contentNode.addChild(pageMaker.getInfobox("requests_in_progress", L10n.getString("QueueToadlet.wipDU", new String[]{ "size" }, new String[]{ String.valueOf(uncompletedDirUpload.size()) })));
			HTMLNode uncompletedContent = pageMaker.getContentNode(uncompletedInfobox);
			if (advancedModeEnabled) {
				uncompletedContent.addChild(createRequestTable(pageMaker, ctx, uncompletedDirUpload, new int[] { LIST_IDENTIFIER, LIST_FILES, LIST_PRIORITY, LIST_TOTAL_SIZE, LIST_PROGRESS, LIST_PERSISTENCE, LIST_KEY }, priorityClasses, advancedModeEnabled, true));
			} else {
				uncompletedContent.addChild(createRequestTable(pageMaker, ctx, uncompletedDirUpload, new int[] { LIST_FILES, LIST_TOTAL_SIZE, LIST_PROGRESS, LIST_PRIORITY, LIST_KEY, LIST_PERSISTENCE }, priorityClasses, advancedModeEnabled, true));
			}
		}
		
		MultiValueTable pageHeaders = new MultiValueTable();
		writeHTMLReply(ctx, 200, "OK", pageHeaders, pageNode.generate());
	}

	
	private HTMLNode createReasonCell(String failureReason) {
		HTMLNode reasonCell = new HTMLNode("td", "class", "request-reason");
		if (failureReason == null) {
			reasonCell.addChild("span", "class", "failure_reason_unknown", L10n.getString("QueueToadlet.unknown"));
		} else {
			reasonCell.addChild("span", "class", "failure_reason_is", failureReason);
		}
		return reasonCell;
	}

	private HTMLNode createProgressCell(boolean started, int fetched, int failed, int fatallyFailed, int min, int total, boolean finalized, boolean upload) {
		HTMLNode progressCell = new HTMLNode("td", "class", "request-progress");
		if (!started) {
			progressCell.addChild("#", L10n.getString("QueueToadlet.starting"));
			return progressCell;
		}
		
		//double frac = p.getSuccessFraction();
		if (!core.isAdvancedModeEnabled()) {
			total = min;
		}
		
		if ((fetched < 0) || (total <= 0)) {
			progressCell.addChild("span", "class", "progress_fraction_unknown", L10n.getString("QueueToadlet.unknown"));
		} else {
			int fetchedPercent = (int) (fetched / (double) total * 100);
			int failedPercent = (int) (failed / (double) total * 100);
			int fatallyFailedPercent = (int) (fatallyFailed / (double) total * 100);
			int minPercent = (int) (min / (double) total * 100);
			HTMLNode progressBar = progressCell.addChild("div", "class", "progressbar");
			progressBar.addChild("div", new String[] { "class", "style" }, new String[] { "progressbar-done", "width: " + fetchedPercent + "%;" });

			if (failed > 0)
				progressBar.addChild("div", new String[] { "class", "style" }, new String[] { "progressbar-failed", "width: " + failedPercent + "%;" });
			if (fatallyFailed > 0)
				progressBar.addChild("div", new String[] { "class", "style" }, new String[] { "progressbar-failed2", "width: " + fatallyFailedPercent + "%;" });
			if ((fetched + failed + fatallyFailed) < min)
				progressBar.addChild("div", new String[] { "class", "style" }, new String[] { "progressbar-min", "width: " + (minPercent - fetchedPercent) + "%;" });
			
			NumberFormat nf = NumberFormat.getInstance();
			nf.setMaximumFractionDigits(1);
			String prefix = '('+Integer.toString(fetched) + "/ " + Integer.toString(min)+"): ";
			if (finalized) {
				progressBar.addChild("div", new String[] { "class", "title" }, new String[] { "progress_fraction_finalized", prefix + L10n.getString("QueueToadlet.progressbarAccurate") }, nf.format((int) ((fetched / (double) min) * 1000) / 10.0) + '%');
			} else {
				String text = nf.format((int) ((fetched / (double) min) * 1000) / 10.0)+ '%';
				if(!finalized)
					text = "" + fetched + " ("+text+"??)";
				progressBar.addChild("div", new String[] { "class", "title" }, new String[] { "progress_fraction_not_finalized", prefix + L10n.getString(upload ? "QueueToadlet.uploadProgressbarNotAccurate" : "QueueToadlet.progressbarNotAccurate") }, text);
			}
		}
		return progressCell;
	}

	private HTMLNode createNumberCell(int numberOfFiles) {
		HTMLNode numberCell = new HTMLNode("td", "class", "request-files");
		numberCell.addChild("span", "class", "number_of_files", String.valueOf(numberOfFiles));
		return numberCell;
	}

	private HTMLNode createFilenameCell(File filename) {
		HTMLNode filenameCell = new HTMLNode("td", "class", "request-filename");
		if (filename != null) {
			filenameCell.addChild("span", "class", "filename_is", filename.toString());
		} else {
			filenameCell.addChild("span", "class", "filename_none", L10n.getString("QueueToadlet.none"));
		}
		return filenameCell;
	}

	private HTMLNode createPriorityCell(PageMaker pageMaker, String identifier, short priorityClass, ToadletContext ctx, String[] priorityClasses, boolean advancedModeEnabled) {
		
		HTMLNode priorityCell = new HTMLNode("td", "class", "request-priority nowrap");
		HTMLNode priorityForm = ctx.addFormChild(priorityCell, "/queue/", "queueChangePriorityCell-" + identifier.hashCode());
		priorityForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "identifier", identifier });
		HTMLNode prioritySelect = priorityForm.addChild("select", "name", "priority");
		for (int p = 0; p < RequestStarter.NUMBER_OF_PRIORITY_CLASSES; p++) {
			if(p <= RequestStarter.INTERACTIVE_PRIORITY_CLASS && !advancedModeEnabled) continue;
			if (p == priorityClass) {
				prioritySelect.addChild("option", new String[] { "value", "selected" }, new String[] { String.valueOf(p), "selected" }, priorityClasses[p]);
			} else {
				prioritySelect.addChild("option", "value", String.valueOf(p), priorityClasses[p]);
			}
		}
		priorityForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "change_priority", L10n.getString("QueueToadlet.change") });
		return priorityCell;
	}

	private HTMLNode createDeleteCell(PageMaker pageMaker, String identifier, ClientRequest clientRequest, ToadletContext ctx) {
		HTMLNode deleteNode = new HTMLNode("td", "class", "request-delete");
		HTMLNode deleteForm = ctx.addFormChild(deleteNode, "/queue/", "queueDeleteForm-" + identifier.hashCode());
		deleteForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "identifier", identifier });
		deleteForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "remove_request", L10n.getString("QueueToadlet.remove") });
		
		// If it's failed, offer to restart it
		
		if(clientRequest.hasFinished() && !clientRequest.hasSucceeded() && clientRequest.canRestart()) {
			HTMLNode retryForm = ctx.addFormChild(deleteNode, "/queue/", "queueRestartForm-" + identifier.hashCode());
			String restartName = L10n.getString(clientRequest instanceof ClientGet && ((ClientGet)clientRequest).hasPermRedirect() ? "QueueToadlet.follow" : "QueueToadlet.restart");
			retryForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "identifier", identifier });
			retryForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "restart_request", restartName });
		}
		
		return deleteNode;
	}
	
	private HTMLNode createPanicBox(PageMaker pageMaker, ToadletContext ctx) {
		HTMLNode panicBox = pageMaker.getInfobox("infobox-alert", L10n.getString("QueueToadlet.panicButton"));
		HTMLNode panicForm = ctx.addFormChild(pageMaker.getContentNode(panicBox), "/queue/", "queuePanicForm");
		panicForm.addChild("#", (L10n.getString("QueueToadlet.panicButtonConfirmation") + ' '));
		panicForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "remove_AllRequests", L10n.getString("QueueToadlet.remove") });
		return panicBox;
	}
	
	private HTMLNode createIdentifierCell(FreenetURI uri, String identifier, boolean directory) {
		HTMLNode identifierCell = new HTMLNode("td", "class", "request-identifier");
		if (uri != null) {
			identifierCell.addChild("span", "class", "identifier_with_uri").addChild("a", "href", "/" + uri + (directory ? "/" : ""), identifier);
		} else {
			identifierCell.addChild("span", "class", "identifier_without_uri", identifier);
		}
		return identifierCell;
	}

	private HTMLNode createPersistenceCell(boolean persistent, boolean persistentForever) {
		HTMLNode persistenceCell = new HTMLNode("td", "class", "request-persistence");
		if (persistentForever) {
			persistenceCell.addChild("span", "class", "persistence_forever", L10n.getString("QueueToadlet.persistenceForever"));
		} else if (persistent) {
			persistenceCell.addChild("span", "class", "persistence_reboot", L10n.getString("QueueToadlet.persistenceReboot"));
		} else {
			persistenceCell.addChild("span", "class", "persistence_none", L10n.getString("QueueToadlet.persistenceNone"));
		}
		return persistenceCell;
	}

	private HTMLNode createDownloadCell(PageMaker pageMaker, ClientGet p) {
		HTMLNode downloadCell = new HTMLNode("td", "class", "request-download");
		downloadCell.addChild("a", "href", p.getURI().toString(), L10n.getString("QueueToadlet.download"));
		return downloadCell;
	}

	private HTMLNode createTypeCell(String type) {
		HTMLNode typeCell = new HTMLNode("td", "class", "request-type");
		if (type != null) {
			typeCell.addChild("span", "class", "mimetype_is", type);
		} else {
			typeCell.addChild("span", "class", "mimetype_unknown", L10n.getString("QueueToadlet.unknown"));
		}
		return typeCell;
	}

	private HTMLNode createSizeCell(long dataSize, boolean confirmed, boolean advancedModeEnabled) {
		HTMLNode sizeCell = new HTMLNode("td", "class", "request-size");
		if (dataSize > 0 && (confirmed || advancedModeEnabled)) {
			sizeCell.addChild("span", "class", "filesize_is", (confirmed ? "" : ">= ") + SizeUtil.formatSize(dataSize) + (confirmed ? "" : " ??"));
		} else {
			sizeCell.addChild("span", "class", "filesize_unknown", L10n.getString("QueueToadlet.unknown"));
		}
		return sizeCell;
	}

	private HTMLNode createKeyCell(FreenetURI uri, boolean addSlash) {
		HTMLNode keyCell = new HTMLNode("td", "class", "request-key");
		if (uri != null) {
			keyCell.addChild("span", "class", "key_is").addChild("a", "href", '/' + uri.toString() + (addSlash ? "/" : ""), uri.toShortString() + (addSlash ? "/" : ""));
		} else {
			keyCell.addChild("span", "class", "key_unknown", L10n.getString("QueueToadlet.unknown"));
		}
		return keyCell;
	}
	
	private HTMLNode createInsertBox(PageMaker pageMaker, ToadletContext ctx, boolean isAdvancedModeEnabled) {
		/* the insert file box */
		HTMLNode insertBox = pageMaker.getInfobox(L10n.getString("QueueToadlet.insertFile"));
		HTMLNode insertContent = pageMaker.getContentNode(insertBox);
		HTMLNode insertForm = ctx.addFormChild(insertContent, "/queue/", "queueInsertForm");
		insertForm.addChild("#", (L10n.getString("QueueToadlet.insertAs") + ' '));
		insertForm.addChild("input", new String[] { "type", "name", "value", "checked" }, new String[] { "radio", "keytype", "chk", "checked" });
		insertForm.addChild("#", " CHK \u00a0 ");
		insertForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "radio", "keytype", "ksk" });
		insertForm.addChild("#", " KSK/SSK/USK \u00a0 ");
		insertForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "text", "key", "KSK@" });
		if(ctx.isAllowedFullAccess()) {
			insertForm.addChild("#", " \u00a0 ");
			insertForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "insert-local", L10n.getString("QueueToadlet.insertFileBrowseLabel") + "..." });
			insertForm.addChild("br");
		}
		insertForm.addChild("#", L10n.getString("QueueToadlet.insertFileLabel") + ": ");
		insertForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "file", "filename", "" });
		insertForm.addChild("#", " \u00a0 ");
		insertForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "insert", L10n.getString("QueueToadlet.insertFileInsertFileLabel") });
		insertForm.addChild("#", " \u00a0 ");
		if(isAdvancedModeEnabled) {
			insertForm.addChild("input", new String[] { "type", "name", "checked" }, new String[] { "checkbox", "compress", "checked" });
			insertForm.addChild("#", " " + L10n.getString("QueueToadlet.insertFileCompressLabel") + " \u00a0 ");
		} else {
			insertForm.addChild("input", new String[] { "type", "value" }, new String[] { "hidden", "true" });
		}
		insertForm.addChild("input", new String[] { "type", "name" }, new String[] { "reset", L10n.getString("QueueToadlet.insertFileResetForm") });
		return insertBox;
	}
	
	private HTMLNode createRequestTable(PageMaker pageMaker, ToadletContext ctx, List requests, int[] columns, String[] priorityClasses, boolean advancedModeEnabled, boolean isUpload) {
		HTMLNode table = new HTMLNode("table", "class", "requests");
		HTMLNode headerRow = table.addChild("tr", "class", "table-header");
		headerRow.addChild("th");

		for (int columnIndex = 0, columnCount = columns.length; columnIndex < columnCount; columnIndex++) {
			int column = columns[columnIndex];
			if (column == LIST_IDENTIFIER) {
				headerRow.addChild("th").addChild("a", "href", (isReversed ? "?sortBy=id" : "?sortBy=id&reversed")).addChild("#", L10n.getString("QueueToadlet.identifier"));
			} else if (column == LIST_SIZE) {
				headerRow.addChild("th").addChild("a", "href", (isReversed ? "?sortBy=size" : "?sortBy=size&reversed")).addChild("#", L10n.getString("QueueToadlet.size"));
			} else if (column == LIST_DOWNLOAD) {
				headerRow.addChild("th", L10n.getString("QueueToadlet.download"));
			} else if (column == LIST_MIME_TYPE) {
				headerRow.addChild("th", L10n.getString("QueueToadlet.mimeType"));
			} else if (column == LIST_PERSISTENCE) {
				headerRow.addChild("th", L10n.getString("QueueToadlet.persistence"));
			} else if (column == LIST_KEY) {
				headerRow.addChild("th", L10n.getString("QueueToadlet.key"));
			} else if (column == LIST_FILENAME) {
				headerRow.addChild("th", L10n.getString("QueueToadlet.fileName"));
			} else if (column == LIST_PRIORITY) {
				headerRow.addChild("th").addChild("a", "href", (isReversed ? "?sortBy=priority" : "?sortBy=priority&reversed")).addChild("#", L10n.getString("QueueToadlet.priority"));
			} else if (column == LIST_FILES) {
				headerRow.addChild("th", L10n.getString("QueueToadlet.files"));
			} else if (column == LIST_TOTAL_SIZE) {
				headerRow.addChild("th", L10n.getString("QueueToadlet.totalSize"));
			} else if (column == LIST_PROGRESS) {
				headerRow.addChild("th").addChild("a", "href", (isReversed ? "?sortBy=progress" : "?sortBy=progress&reversed")).addChild("#", L10n.getString("QueueToadlet.progress"));
			} else if (column == LIST_REASON) {
				headerRow.addChild("th", L10n.getString("QueueToadlet.reason"));
			}
		}
		for (Iterator requestItems = requests.iterator(); requestItems.hasNext(); ) {
			ClientRequest clientRequest = (ClientRequest) requestItems.next();
			HTMLNode requestRow = table.addChild("tr", "class", "priority" + clientRequest.getPriority());
			
			requestRow.addChild(createDeleteCell(pageMaker, clientRequest.getIdentifier(), clientRequest, ctx));
			for (int columnIndex = 0, columnCount = columns.length; columnIndex < columnCount; columnIndex++) {
				int column = columns[columnIndex];
				if (column == LIST_IDENTIFIER) {
					if (clientRequest instanceof ClientGet) {
						requestRow.addChild(createIdentifierCell(((ClientGet) clientRequest).getURI(), clientRequest.getIdentifier(), false));
					} else if (clientRequest instanceof ClientPutDir) {
						requestRow.addChild(createIdentifierCell(((ClientPutDir) clientRequest).getFinalURI(), clientRequest.getIdentifier(), true));
					} else if (clientRequest instanceof ClientPut) {
						requestRow.addChild(createIdentifierCell(((ClientPut) clientRequest).getFinalURI(), clientRequest.getIdentifier(), false));
					}
				} else if (column == LIST_SIZE) {
					if (clientRequest instanceof ClientGet) {
						requestRow.addChild(createSizeCell(((ClientGet) clientRequest).getDataSize(), ((ClientGet) clientRequest).isTotalFinalized(), advancedModeEnabled));
					} else if (clientRequest instanceof ClientPut) {
						requestRow.addChild(createSizeCell(((ClientPut) clientRequest).getDataSize(), true, advancedModeEnabled));
					}
				} else if (column == LIST_DOWNLOAD) {
					requestRow.addChild(createDownloadCell(pageMaker, (ClientGet) clientRequest));
				} else if (column == LIST_MIME_TYPE) {
					if (clientRequest instanceof ClientGet) {
						requestRow.addChild(createTypeCell(((ClientGet) clientRequest).getMIMEType()));
					} else if (clientRequest instanceof ClientPut) {
						requestRow.addChild(createTypeCell(((ClientPut) clientRequest).getMIMEType()));
					}
				} else if (column == LIST_PERSISTENCE) {
					requestRow.addChild(createPersistenceCell(clientRequest.isPersistent(), clientRequest.isPersistentForever()));
				} else if (column == LIST_KEY) {
					if (clientRequest instanceof ClientGet) {
						requestRow.addChild(createKeyCell(((ClientGet) clientRequest).getURI(), false));
					} else if (clientRequest instanceof ClientPut) {
						requestRow.addChild(createKeyCell(((ClientPut) clientRequest).getFinalURI(), false));
					}else {
						requestRow.addChild(createKeyCell(((ClientPutDir) clientRequest).getFinalURI(), true));
					}
				} else if (column == LIST_FILENAME) {
					if (clientRequest instanceof ClientGet) {
						requestRow.addChild(createFilenameCell(((ClientGet) clientRequest).getDestFilename()));
					} else if (clientRequest instanceof ClientPut) {
						requestRow.addChild(createFilenameCell(((ClientPut) clientRequest).getOrigFilename()));
					}
				} else if (column == LIST_PRIORITY) {
					requestRow.addChild(createPriorityCell(pageMaker, clientRequest.getIdentifier(), clientRequest.getPriority(), ctx, priorityClasses, advancedModeEnabled));
				} else if (column == LIST_FILES) {
					requestRow.addChild(createNumberCell(((ClientPutDir) clientRequest).getNumberOfFiles()));
				} else if (column == LIST_TOTAL_SIZE) {
					requestRow.addChild(createSizeCell(((ClientPutDir) clientRequest).getTotalDataSize(), true, advancedModeEnabled));
				} else if (column == LIST_PROGRESS) {
					requestRow.addChild(createProgressCell(clientRequest.isStarted(), (int) clientRequest.getFetchedBlocks(), (int) clientRequest.getFailedBlocks(), (int) clientRequest.getFatalyFailedBlocks(), (int) clientRequest.getMinBlocks(), (int) clientRequest.getTotalBlocks(), clientRequest.isTotalFinalized() || clientRequest instanceof ClientPut, isUpload));
				} else if (column == LIST_REASON) {
					requestRow.addChild(createReasonCell(clientRequest.getFailureReason()));
				}
			}
		}
		return table;
	}

	public String supportedMethods() {
		return "GET, POST";
	}

	/**
	 * List of completed request identifiers which the user hasn't acknowledged yet.
	 */
	private final HashSet completedRequestIdentifiers = new HashSet();
	
	private final HashMap alertsByIdentifier = new HashMap();
	
	public void notifyFailure(ClientRequest req) {
		// FIXME do something???
	}

	public void notifySuccess(ClientRequest req) {
		synchronized(completedRequestIdentifiers) {
			completedRequestIdentifiers.add(req.getIdentifier());
		}
		registerAlert(req);
		saveCompletedIdentifiersOffThread();
	}
	
	private void saveCompletedIdentifiersOffThread() {
		core.getExecutor().execute(new Runnable() {
			public void run() {
				saveCompletedIdentifiers();
			}
		}, "Save completed identifiers");
	}

	private void loadCompletedIdentifiers() {
		File completedIdentifiersList = new File(core.node.getNodeDir(), "completed.list");
		File completedIdentifiersListNew = new File(core.node.getNodeDir(), "completed.list.bak");
		if(!readCompletedIdentifiers(completedIdentifiersList)) {
			readCompletedIdentifiers(completedIdentifiersListNew);
		}
		core.clientContext.jobRunner.queue(new DBJob() {

			public void run(ObjectContainer container, ClientContext context) {
				String[] identifiers;
				synchronized(completedRequestIdentifiers) {
					identifiers = (String[]) completedRequestIdentifiers.toArray(new String[completedRequestIdentifiers.size()]);
				}
				for(int i=0;i<identifiers.length;i++) {
					ClientRequest req = fcp.getGlobalRequest(identifiers[i], container);
					if(req == null) {
						synchronized(completedRequestIdentifiers) {
							completedRequestIdentifiers.remove(identifiers[i]);
						}
						continue;
					}
					registerAlert(req);
				}
			}
			
		}, NativeThread.HIGH_PRIORITY, false);
	}
	
	private boolean readCompletedIdentifiers(File file) {
		FileInputStream fis = null;
		try {
			fis = new FileInputStream(file);
			BufferedInputStream bis = new BufferedInputStream(fis);
			InputStreamReader isr = new InputStreamReader(bis, "UTF-8");
			BufferedReader br = new BufferedReader(isr);
			synchronized(completedRequestIdentifiers) {
				completedRequestIdentifiers.clear();
				while(true) {
					String identifier = br.readLine();
					if(identifier == null) return true;
					completedRequestIdentifiers.add(identifier);
				}
			}
		} catch (EOFException e) {
			// Normal
			return true;
		} catch (FileNotFoundException e) {
			// Normal
			return false;
		} catch (UnsupportedEncodingException e) {
			throw new Error("Impossible: JVM doesn't support UTF-8: " + e, e);
		} catch (IOException e) {
			Logger.error(this, "Could not read completed identifiers list from "+file);
			return false;
		} finally {
			Closer.close(fis);
		}
	}

	private void saveCompletedIdentifiers() {
		FileOutputStream fos = null;
		BufferedWriter bw = null;
		File completedIdentifiersList = new File(core.node.getNodeDir(), "completed.list");
		File completedIdentifiersListNew = new File(core.node.getNodeDir(), "completed.list.bak");
		File temp;
		try {
			temp = File.createTempFile("completed.list", ".tmp", core.node.getNodeDir());
			temp.deleteOnExit();
			fos = new FileOutputStream(temp);
			OutputStreamWriter osw = new OutputStreamWriter(fos, "UTF-8");
			bw = new BufferedWriter(osw);
			String[] identifiers;
			synchronized(completedRequestIdentifiers) {
				identifiers = (String[]) completedRequestIdentifiers.toArray(new String[completedRequestIdentifiers.size()]);
			}
			for(int i=0;i<identifiers.length;i++)
				bw.write(identifiers[i]+'\n');
		} catch (FileNotFoundException e) {
			Logger.error(this, "Unable to save completed requests list (can't find node directory?!!?): "+e, e);
			return;
		} catch (IOException e) {
			Logger.error(this, "Unable to save completed requests list: "+e, e);
			return;
		} finally {
			if(bw != null) {
				try {
					bw.close();
				} catch (IOException e) {
					try {
						fos.close();
					} catch (IOException e1) {
						// Ignore
					}
				}
			} else {
				try {
					fos.close();
				} catch (IOException e1) {
					// Ignore
				}
			}
		}
		completedIdentifiersListNew.delete();
		temp.renameTo(completedIdentifiersListNew);
		if(!completedIdentifiersListNew.renameTo(completedIdentifiersList)) {
			completedIdentifiersList.delete();
			if(!completedIdentifiersListNew.renameTo(completedIdentifiersList)) {
				Logger.error(this, "Unable to store completed identifiers list because unable to rename "+completedIdentifiersListNew+" to "+completedIdentifiersList);
			}
		}
	}

	private void registerAlert(ClientRequest req) {
		final String identifier = req.getIdentifier();
		if(req instanceof ClientGet) {
			FreenetURI uri = ((ClientGet)req).getURI();
			long size = ((ClientGet)req).getDataSize();
			String name = uri.getPreferredFilename();
			String title = l10n("downloadSucceededTitle", "filename", name);
			HTMLNode text = new HTMLNode("div");
			L10n.addL10nSubstitution(text, "QueueToadlet.downloadSucceeded",
					new String[] { "link", "/link", "origlink", "/origlink", "filename", "size" },
					new String[] { "<a href=\"/queue/"+uri.toACIIString()+"\">", "</a>", "<a href=\"/"+uri.toACIIString()+"\">", "</a>", name, SizeUtil.formatSize(size) } );
			UserAlert alert = 
			new SimpleHTMLUserAlert(true, title, title, text, UserAlert.MINOR) {
				public void onDismiss() {
					synchronized(completedRequestIdentifiers) {
						completedRequestIdentifiers.remove(identifier);
					}
					synchronized(alertsByIdentifier) {
						alertsByIdentifier.remove(identifier);
					}
					saveCompletedIdentifiersOffThread();
				}
				public boolean isEventNotification() {
					return true;
				}
			};
			core.alerts.register(alert);
			synchronized(alertsByIdentifier) {
				alertsByIdentifier.put(identifier, alert);
			}
		} else if(req instanceof ClientPut) {
			FreenetURI uri = ((ClientPut)req).getFinalURI();
			long size = ((ClientPut)req).getDataSize();
			String name = uri.getPreferredFilename();
			String title = l10n("uploadSucceededTitle", "filename", name);
			HTMLNode text = new HTMLNode("div");
			L10n.addL10nSubstitution(text, "QueueToadlet.uploadSucceeded",
					new String[] { "link", "/link", "filename", "size" },
					new String[] { "<a href=\"/"+uri.toACIIString()+"\">", "</a>", name, SizeUtil.formatSize(size) } );
			UserAlert alert = 
			new SimpleHTMLUserAlert(true, title, title, text, UserAlert.MINOR) {
				public void onDismiss() {
					synchronized(completedRequestIdentifiers) {
						completedRequestIdentifiers.remove(identifier);
					}
					synchronized(alertsByIdentifier) {
						alertsByIdentifier.remove(identifier);
					}
					saveCompletedIdentifiersOffThread();
				}
				public boolean isEventNotification() {
					return true;
				}
			};
			core.alerts.register(alert);
			synchronized(alertsByIdentifier) {
				alertsByIdentifier.put(identifier, alert);
			}
		} else if(req instanceof ClientPutDir) {
			FreenetURI uri = ((ClientPutDir)req).getFinalURI();
			long size = ((ClientPutDir)req).getTotalDataSize();
			int files = ((ClientPutDir)req).getNumberOfFiles();
			String name = uri.getPreferredFilename();
			String title = l10n("siteUploadSucceededTitle", "filename", name);
			HTMLNode text = new HTMLNode("div");
			L10n.addL10nSubstitution(text, "QueueToadlet.siteUploadSucceeded",
					new String[] { "link", "/link", "filename", "size", "files" },
					new String[] { "<a href=\"/"+uri.toACIIString()+"\">", "</a>", name, SizeUtil.formatSize(size), Integer.toString(files) } );
			UserAlert alert = 
			new SimpleHTMLUserAlert(true, title, title, text, UserAlert.MINOR) {
				public void onDismiss() {
					synchronized(completedRequestIdentifiers) {
						completedRequestIdentifiers.remove(identifier);
					}
					synchronized(alertsByIdentifier) {
						alertsByIdentifier.remove(identifier);
					}
					saveCompletedIdentifiersOffThread();
				}
				public boolean isEventNotification() {
					return true;
				}
			};
			core.alerts.register(alert);
			synchronized(alertsByIdentifier) {
				alertsByIdentifier.put(identifier, alert);
			}
		}
	}

	String l10n(String key, String pattern, String value) {
		return L10n.getString("QueueToadlet."+key, pattern, value);
	}

	public void onRemove(ClientRequest req) {
		String identifier = req.getIdentifier();
		synchronized(completedRequestIdentifiers) {
			completedRequestIdentifiers.remove(identifier);
		}
		UserAlert alert;
		synchronized(alertsByIdentifier) {
			alert = (UserAlert) alertsByIdentifier.remove(identifier);
		}
		core.alerts.unregister(alert);
		saveCompletedIdentifiersOffThread();
	}

}
