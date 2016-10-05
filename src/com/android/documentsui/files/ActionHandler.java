/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.documentsui.files;

import static com.android.documentsui.base.Shared.DEBUG;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Log;

import com.android.documentsui.AbstractActionHandler;
import com.android.documentsui.DocumentsAccess;
import com.android.documentsui.DocumentsApplication;
import com.android.documentsui.Metrics;
import com.android.documentsui.base.ConfirmationCallback;
import com.android.documentsui.base.ConfirmationCallback.Result;
import com.android.documentsui.base.DocumentInfo;
import com.android.documentsui.base.DocumentStack;
import com.android.documentsui.base.Lookup;
import com.android.documentsui.base.RootInfo;
import com.android.documentsui.base.State;
import com.android.documentsui.clipping.ClipStore;
import com.android.documentsui.clipping.DocumentClipper;
import com.android.documentsui.clipping.UrisSupplier;
import com.android.documentsui.dirlist.AnimationView;
import com.android.documentsui.dirlist.DocumentDetails;
import com.android.documentsui.dirlist.FragmentTuner;
import com.android.documentsui.dirlist.Model;
import com.android.documentsui.dirlist.MultiSelectManager;
import com.android.documentsui.dirlist.MultiSelectManager.Selection;
import com.android.documentsui.files.ActionHandler.Addons;
import com.android.documentsui.roots.GetRootDocumentTask;
import com.android.documentsui.roots.RootsAccess;
import com.android.documentsui.services.FileOperation;
import com.android.documentsui.services.FileOperationService;
import com.android.documentsui.services.FileOperations;
import com.android.documentsui.ui.DialogController;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executor;

import javax.annotation.Nullable;

/**
 * Provides {@link FilesActivity} action specializations to fragments.
 */
public class ActionHandler<T extends Activity & Addons> extends AbstractActionHandler<T> {

    private static final String TAG = "ManagerActionHandler";

    private final DialogController mDialogs;
    private final FragmentTuner mTuner;
    private final DocumentClipper mClipper;
    private final ClipStore mClipStore;

    private final Config mConfig;

    ActionHandler(
            T activity,
            State state,
            RootsAccess roots,
            DocumentsAccess docs,
            Lookup<String, Executor> executors,
            DialogController dialogs,
            FragmentTuner tuner,
            DocumentClipper clipper,
            ClipStore clipStore) {

        super(activity, state, roots, docs, executors);

        mDialogs = dialogs;
        mTuner = tuner;
        mClipper = clipper;
        mClipStore = clipStore;

        mConfig = new Config();
    }

    @Override
    public boolean dropOn(ClipData data, RootInfo root) {
        new GetRootDocumentTask(
                root,
                mActivity,
                mActivity::isDestroyed,
                (DocumentInfo doc) -> mClipper.copyFromClipData(
                        root, doc, data, mDialogs::showFileOperationFailures)
        ).executeOnExecutor(mExecutors.lookup(root.authority));
        return true;
    }

    @Override
    public void openSettings(RootInfo root) {
        Metrics.logUserAction(mActivity, Metrics.USER_ACTION_SETTINGS);
        final Intent intent = new Intent(DocumentsContract.ACTION_DOCUMENT_ROOT_SETTINGS);
        intent.setDataAndType(root.getUri(), DocumentsContract.Root.MIME_TYPE_ITEM);
        mActivity.startActivity(intent);
    }

    @Override
    public void pasteIntoFolder(RootInfo root) {
        new GetRootDocumentTask(
                root,
                mActivity,
                mActivity::isDestroyed,
                (DocumentInfo doc) -> pasteIntoFolder(root, doc)
        ).executeOnExecutor(mExecutors.lookup(root.authority));
    }

    private void pasteIntoFolder(RootInfo root, DocumentInfo doc) {
        DocumentClipper clipper = DocumentsApplication.getDocumentClipper(mActivity);
        DocumentStack stack = new DocumentStack(root, doc);
        clipper.copyFromClipboard(doc, stack, mDialogs::showFileOperationFailures);
    }

    @Override
    public void openRoot(RootInfo root) {
        Metrics.logRootVisited(mActivity, root);
        mActivity.onRootPicked(root);
    }

    @Override
    public boolean openDocument(DocumentDetails details) {
        DocumentInfo doc = mConfig.model.getDocument(details.getModelId());
        if (doc == null) {
            Log.w(TAG,
                    "Can't view item. No Document available for modeId: " + details.getModelId());
            return false;
        }

        if (mTuner.isDocumentEnabled(doc.mimeType, doc.flags)) {
            onDocumentPicked(doc);
            mConfig.selectionMgr.clearSelection();
            return true;
        }
        return false;
    }

    @Override
    public boolean viewDocument(DocumentDetails details) {
        DocumentInfo doc = mConfig.model.getDocument(details.getModelId());
        return viewDocument(doc);
    }

    @Override
    public boolean previewDocument(DocumentDetails details) {
        DocumentInfo doc = mConfig.model.getDocument(details.getModelId());
        if (doc.isContainer()) {
            return false;
        }
        return previewDocument(doc);
    }

    @Override
    public void deleteDocuments(Model model, Selection selected, ConfirmationCallback callback) {
        Metrics.logUserAction(mActivity, Metrics.USER_ACTION_DELETE);

        assert(!selected.isEmpty());

        final DocumentInfo srcParent = mState.stack.peek();
        assert(srcParent != null);

        // Model must be accessed in UI thread, since underlying cursor is not threadsafe.
        List<DocumentInfo> docs = model.getDocuments(selected);

        ConfirmationCallback result = (@Result int code) -> {
            // share the news with our caller, be it good or bad.
            callback.accept(code);

            if (code != ConfirmationCallback.CONFIRM) {
                return;
            }

            UrisSupplier srcs;
            try {
                srcs = UrisSupplier.create(
                        selected,
                        model::getItemUri,
                        mClipStore);
            } catch (IOException e) {
                throw new RuntimeException("Failed to create uri supplier.", e);
            }

            FileOperation operation = new FileOperation.Builder()
                    .withOpType(FileOperationService.OPERATION_DELETE)
                    .withDestination(mState.stack)
                    .withSrcs(srcs)
                    .withSrcParent(srcParent.derivedUri)
                    .build();

            FileOperations.start(mActivity, operation, mDialogs::showFileOperationFailures);
        };

        mDialogs.confirmDelete(docs, result);
    }

    @Override
    public void initLocation(Intent intent) {
        assert(intent != null);

        if (mState.restored) {
            if (DEBUG) Log.d(TAG, "Stack already resolved for uri: " + intent.getData());
            return;
        }

        if (launchToStackLocation(mState.stack)) {
            if (DEBUG) Log.d(TAG, "Launched to location from stack.");
            return;
        }

        if (launchToDocument(intent)) {
            if (DEBUG) Log.d(TAG, "Launched to root for viewing (likely a ZIP).");
            return;
        }

        if (launchToRoot(intent)) {
            if (DEBUG) Log.d(TAG, "Launched to root for browsing.");
            return;
        }

        if (DEBUG) Log.d(TAG, "Launching directly into Home directory.");
        loadHomeDir();
    }

    // If a non-empty stack is present in our state, it was read (presumably)
    // from EXTRA_STACK intent extra. In this case, we'll skip other means of
    // loading or restoring the stack (like URI).
    //
    // When restoring from a stack, if a URI is present, it should only ever be:
    // -- a launch URI: Launch URIs support sensible activity management,
    //    but don't specify a real content target)
    // -- a fake Uri from notifications. These URIs have no authority (TODO: details).
    //
    // Any other URI is *sorta* unexpected...except when browsing an archive
    // in downloads.
    private boolean launchToStackLocation(DocumentStack stack) {
        if (stack == null || stack.root == null) {
            return false;
        }

        if (mState.stack.isEmpty()) {
            mActivity.onRootPicked(mState.stack.root);
        } else {
            mActivity.refreshCurrentRootAndDirectory(AnimationView.ANIM_NONE);
        }

        return true;
    }

    // Zips in downloads are not opened inline, because of Downloads no-folders policy.
    // So we're registered to handle VIEWs of zips.
    private boolean launchToDocument(Intent intent) {
        if (Intent.ACTION_VIEW.equals(intent.getAction())) {
            Uri uri = intent.getData();
            assert(uri != null);
            loadDocument(uri);
            return true;
        }

        return false;
    }

    private boolean launchToRoot(Intent intent) {
        if (DocumentsContract.ACTION_BROWSE.equals(intent.getAction())) {
            Uri uri = intent.getData();
            if (DocumentsContract.isRootUri(mActivity, uri)) {
                if (DEBUG) Log.d(TAG, "Launching with root URI.");
                // If we've got a specific root to display, restore that root using a dedicated
                // authority. That way a misbehaving provider won't result in an ANR.
                loadRoot(uri);
                return true;
            }
        }
        return false;
    }

    public void showChooserForDoc(DocumentInfo doc) {
        assert(!doc.isContainer());

        if (manageDocument(doc)) {
            Log.w(TAG, "Open with is not yet supported for managed doc.");
            return;
        }

        Intent intent = Intent.createChooser(buildViewIntent(doc), null);
        try {
            mActivity.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            mDialogs.showNoApplicationFound();
        }
    }

    public void onDocumentPicked(DocumentInfo doc) {
        if (doc.isContainer()) {
            mActivity.openContainerDocument(doc);
            return;
        }

        if (manageDocument(doc)) {
            return;
        }

        if (previewDocument(doc)) {
            return;
        }

        viewDocument(doc);
    }

    public boolean viewDocument(DocumentInfo doc) {
        if (doc.isPartial()) {
            Log.w(TAG, "Can't view partial file.");
            return false;
        }

        if (doc.isContainer()) {
            mActivity.openContainerDocument(doc);
            return true;
        }

        // this is a redundant check.
        if (manageDocument(doc)) {
            return true;
        }

        // Fall back to traditional VIEW action...
        Intent intent = buildViewIntent(doc);
        if (DEBUG && intent.getClipData() != null) {
            Log.d(TAG, "Starting intent w/ clip data: " + intent.getClipData());
        }

        try {
            mActivity.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException e) {
            mDialogs.showNoApplicationFound();
        }
        return false;
    }

    public boolean previewDocument(DocumentInfo doc) {
        if (doc.isPartial()) {
            Log.w(TAG, "Can't view partial file.");
            return false;
        }

        Intent intent = new QuickViewIntentBuilder(
                mActivity.getPackageManager(),
                mActivity.getResources(),
                doc,
                mConfig.model).build();

        if (intent != null) {
            // TODO: un-work around issue b/24963914. Should be fixed soon.
            try {
                mActivity.startActivity(intent);
                return true;
            } catch (SecurityException e) {
                // Carry on to regular view mode.
                Log.e(TAG, "Caught security error: " + e.getLocalizedMessage());
            }
        }

        return false;
    }

    private boolean manageDocument(DocumentInfo doc) {
        if (isManagedDownload(doc)) {
            // First try managing the document; we expect manager to filter
            // based on authority, so we don't grant.
            Intent manage = new Intent(DocumentsContract.ACTION_MANAGE_DOCUMENT);
            manage.setData(doc.derivedUri);
            try {
                mActivity.startActivity(manage);
                return true;
            } catch (ActivityNotFoundException ex) {
                // Fall back to regular handling.
            }
        }

        return false;
    }

    private boolean isManagedDownload(DocumentInfo doc) {
        // Anything on downloads goes through the back through downloads manager
        // (that's the MANAGE_DOCUMENT bit).
        // This is done for two reasons:
        // 1) The file in question might be a failed/queued or otherwise have some
        //    specialized download handling.
        // 2) For APKs, the download manager will add on some important security stuff
        //    like origin URL.
        // 3) For partial files, the download manager will offer to restart/retry downloads.

        // All other files not on downloads, event APKs, would get no benefit from this
        // treatment, thusly the "isDownloads" check.

        // Launch MANAGE_DOCUMENTS only for the root level files, so it's not called for
        // files in archives. Also, if the activity is already browsing a ZIP from downloads,
        // then skip MANAGE_DOCUMENTS.
        if (Intent.ACTION_VIEW.equals(mActivity.getIntent().getAction())
                && mState.stack.size() > 1) {
            // viewing the contents of an archive.
            return false;
        }

        // managament is only supported in downloads.
        if (mActivity.getCurrentRoot().isDownloads()) {
            // and only and only on APKs or partial files.
            return "application/vnd.android.package-archive".equals(doc.mimeType)
                    || doc.isPartial();
        }

        return false;
    }

    private Intent buildViewIntent(DocumentInfo doc) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(doc.derivedUri, doc.mimeType);

        // Downloads has traditionally added the WRITE permission
        // in the TrampolineActivity. Since this behavior is long
        // established, we set the same permission for non-managed files
        // This ensures consistent behavior between the Downloads root
        // and other roots.
        int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION;
        if (doc.isWriteSupported()) {
            flags |= Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        }
        intent.setFlags(flags);

        return intent;
    }

    ActionHandler<T> reset(Model model, MultiSelectManager selectionMgr) {
        mConfig.reset(model, selectionMgr);
        return this;
    }

    private static final class Config {

        @Nullable Model model;
        @Nullable MultiSelectManager selectionMgr;

        public void reset(Model model, MultiSelectManager selectionMgr) {
            assert(model != null);

            this.model = model;
            this.selectionMgr = selectionMgr;
        }
    }

    public interface Addons extends CommonAddons {
    }
}