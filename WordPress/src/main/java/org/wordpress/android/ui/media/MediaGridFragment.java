package org.wordpress.android.ui.media;

import android.app.Activity;
import android.app.Fragment;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.view.ActionMode;
import android.support.v7.widget.DefaultItemAnimator;
import android.support.v7.widget.GridLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.wordpress.android.R;
import org.wordpress.android.WordPress;
import org.wordpress.android.fluxc.Dispatcher;
import org.wordpress.android.fluxc.generated.MediaActionBuilder;
import org.wordpress.android.fluxc.model.MediaModel;
import org.wordpress.android.fluxc.model.MediaModel.MediaUploadState;
import org.wordpress.android.fluxc.model.SiteModel;
import org.wordpress.android.fluxc.store.MediaStore;
import org.wordpress.android.fluxc.store.MediaStore.FetchMediaListPayload;
import org.wordpress.android.fluxc.store.MediaStore.MediaErrorType;
import org.wordpress.android.fluxc.store.MediaStore.OnMediaListFetched;
import org.wordpress.android.fluxc.utils.MediaUtils;
import org.wordpress.android.ui.EmptyViewMessageType;
import org.wordpress.android.ui.media.MediaBrowserActivity.MediaBrowserType;
import org.wordpress.android.ui.media.MediaGridAdapter.MediaGridAdapterCallback;
import org.wordpress.android.ui.media.services.MediaDeleteService;
import org.wordpress.android.util.AppLog;
import org.wordpress.android.util.ListUtils;
import org.wordpress.android.util.NetworkUtils;
import org.wordpress.android.util.ToastUtils;
import org.wordpress.android.util.WPActivityUtils;
import org.wordpress.android.util.WPMediaUtils;
import org.wordpress.android.util.helpers.SwipeToRefreshHelper;
import org.wordpress.android.util.helpers.SwipeToRefreshHelper.RefreshListener;
import org.wordpress.android.util.widgets.CustomSwipeRefreshLayout;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

import static android.app.Activity.RESULT_OK;
import static org.wordpress.android.util.WPSwipeToRefreshHelper.buildSwipeToRefreshHelper;

/**
 * The grid displaying the media items.
 */
@SuppressWarnings("ALL")
public class MediaGridFragment extends Fragment implements MediaGridAdapterCallback {
    private static final String BUNDLE_SELECTED_STATES = "BUNDLE_SELECTED_STATES";
    private static final String BUNDLE_IN_MULTI_SELECT_MODE = "BUNDLE_IN_MULTI_SELECT_MODE";
    private static final String BUNDLE_SCROLL_POSITION = "BUNDLE_SCROLL_POSITION";
    private static final String BUNDLE_RETRIEVED_ALL_FILTERS = "BUNDLE_RETRIEVED_ALL_FILTERS";
    private static final String BUNDLE_FETCHED_FILTERS = "BUNDLE_FETCHED_FILTERS";
    private static final String BUNDLE_EMPTY_VIEW_MESSAGE = "BUNDLE_EMPTY_VIEW_MESSAGE";

    static final String TAG = "media_grid_fragment";

    // should be a multiple of both the column counts (3 in portrait, 4 in landscape)
    private static final int NUM_MEDIA_PER_FETCH = 48;

    enum MediaFilter {
        FILTER_ALL(0),
        FILTER_IMAGES(1),
        FILTER_DOCUMENTS(2),
        FILTER_VIDEOS(3),
        FILTER_AUDIO(4);

        private final int value;
        private MediaFilter(int value) {
            this.value = value;
        }
        int getValue() {
            return value;
        }
        private String toMimeType() {
            switch (this) {
                case FILTER_AUDIO:
                    return MediaUtils.MIME_TYPE_AUDIO;
                case FILTER_DOCUMENTS:
                    return MediaUtils.MIME_TYPE_APPLICATION;
                case FILTER_IMAGES:
                    return MediaUtils.MIME_TYPE_IMAGE;
                case FILTER_VIDEOS:
                    return MediaUtils.MIME_TYPE_VIDEO;
                default:
                    return null;
            }
        }
        private static MediaFilter fromMimeType(@NonNull String mimeType) {
            switch (mimeType) {
                case MediaUtils.MIME_TYPE_APPLICATION:
                    return MediaFilter.FILTER_DOCUMENTS;
                case MediaUtils.MIME_TYPE_AUDIO:
                    return MediaFilter.FILTER_AUDIO;
                case MediaUtils.MIME_TYPE_IMAGE:
                    return MediaFilter.FILTER_IMAGES;
                case MediaUtils.MIME_TYPE_VIDEO:
                    return MediaFilter.FILTER_VIDEOS;
                default:
                    return MediaFilter.FILTER_ALL;
            }
        }
    }

    // describes which filters we've fetched media for
    private boolean[] mFetchedFilters = new boolean[MediaFilter.values().length];

    // describes which filters we've fetched ALL media for
    private boolean[] mFetchedAllFilters = new boolean[MediaFilter.values().length];

    @Inject Dispatcher mDispatcher;
    @Inject MediaStore mMediaStore;

    private MediaBrowserType mBrowserType;

    private RecyclerView mRecycler;
    private GridLayoutManager mGridManager;
    private MediaGridAdapter mGridAdapter;
    private MediaGridListener mListener;

    private boolean mIsRefreshing;

    private ActionMode mActionMode;
    private String mSearchTerm;
    private MediaFilter mFilter = MediaFilter.FILTER_ALL;

    private SwipeToRefreshHelper mSwipeToRefreshHelper;

    private TextView mEmptyView;
    private EmptyViewMessageType mEmptyViewMessageType = EmptyViewMessageType.NO_CONTENT;

    private SiteModel mSite;

    public interface MediaGridListener {
        void onMediaItemSelected(View sourceView, int localMediaId, boolean isLongClick);
        void onMediaRequestRetry(int localMediaId);
        void onMediaRequestDelete(int localMediaId);
    }

    public static MediaGridFragment newInstance(@NonNull SiteModel site,
                                                @NonNull MediaBrowserType browserType,
                                                @NonNull MediaFilter filter) {
        Bundle args = new Bundle();
        args.putSerializable(WordPress.SITE, site);
        args.putSerializable(MediaBrowserActivity.ARG_BROWSER_TYPE, browserType);
        args.putSerializable(MediaBrowserActivity.ARG_FILTER, filter);

        MediaGridFragment fragment = new MediaGridFragment();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ((WordPress) getActivity().getApplication()).component().inject(this);

        Bundle args = getArguments();
        mSite = (SiteModel) args.getSerializable(WordPress.SITE);
        mBrowserType = (MediaBrowserType) args.getSerializable(MediaBrowserActivity.ARG_BROWSER_TYPE);
        mFilter = (MediaFilter) args.getSerializable(MediaBrowserActivity.ARG_FILTER);

        if (mSite == null) {
            ToastUtils.showToast(getActivity(), R.string.blog_not_found, ToastUtils.Duration.SHORT);
            getActivity().finish();
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        mDispatcher.register(this);
    }

    @Override
    public void onStop() {
        mDispatcher.unregister(this);
        super.onStop();
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        saveState(outState);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        super.onCreateView(inflater, container, savedInstanceState);

        View view = inflater.inflate(R.layout.media_grid_fragment, container, false);

        mRecycler = (RecyclerView) view.findViewById(R.id.recycler);
        mRecycler.setHasFixedSize(true);

        int numColumns = MediaGridAdapter.getColumnCount(getActivity());
        mGridManager = new GridLayoutManager(getActivity(), numColumns);
        mRecycler.setLayoutManager(mGridManager);
        mRecycler.setAdapter(getAdapter());

        // disable thumbnail loading during a fling to conserve memory
        final int minDistance = WPMediaUtils.getFlingDistanceToDisableThumbLoading(getActivity());
        mRecycler.setOnFlingListener(new RecyclerView.OnFlingListener() {
            @Override
            public boolean onFling(int velocityX, int velocityY) {
                if (Math.abs(velocityY) > minDistance) {
                    getAdapter().setLoadThumbnails(false);
                }
                return false;
            }
        });
        mRecycler.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    getAdapter().setLoadThumbnails(true);
                }
            }
        });

        mEmptyView = (TextView) view.findViewById(R.id.empty_view);

        // swipe to refresh setup
        mSwipeToRefreshHelper = buildSwipeToRefreshHelper(
                (CustomSwipeRefreshLayout) view.findViewById(R.id.ptr_layout), new RefreshListener() {
                    @Override
                    public void onRefreshStarted() {
                        if (!isAdded()) {
                            return;
                        }
                        if (!NetworkUtils.checkConnection(getActivity())) {
                            updateEmptyView(EmptyViewMessageType.NETWORK_ERROR);
                            setRefreshing(false);
                            return;
                        }
                        fetchMediaList(false);
                    }
                }
        );

        if (savedInstanceState != null) {
            restoreState(savedInstanceState);
        }

        setFilter(mFilter);

        return view;
    }

    private boolean hasAdapter() {
        return mGridAdapter != null;
    }

    private MediaGridAdapter getAdapter() {
        if (!hasAdapter()) {
            mGridAdapter = new MediaGridAdapter(getActivity(), mSite, mBrowserType);
            mGridAdapter.setCallback(this);
        }
        return mGridAdapter;
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);

        try {
            mListener = (MediaGridListener) activity;
        } catch (ClassCastException e) {
            throw new ClassCastException(activity.toString() + " must implement MediaGridListener");
        }
    }

    boolean isEmpty() {
        return hasAdapter() && getAdapter().isEmpty();
    }

    MediaFilter getFilter() {
        return mFilter;
    }

    /*
     * called when we know we've retrieved and fetched all media for all filters
     */
    private void setHasFetchedMediaForAllFilters() {
        for (int i = 0; i < mFetchedAllFilters.length; i++) {
            mFetchedFilters[i] = true;
            mFetchedAllFilters[i] = true;
        }
    }

    /*
      * this method has two purposes: (1) make sure media that is being deleted still has the right UploadState,
      * as it may have been overwritten by a refresh while deletion was still in progress, (2) remove any local
      * files (ie: media not uploaded yet) that no longer exist (in case user deleted them from the device)
      */
    private void ensureCorrectState(List<MediaModel> mediaModels) {
        if (isAdded() && getActivity() instanceof MediaBrowserActivity) {
            // we only need to check the deletion state if media are currently being deleted
            MediaDeleteService service = ((MediaBrowserActivity)getActivity()).getMediaDeleteService();
            boolean checkDeleteState = service != null && service.isAnyMediaBeingDeleted();

            // note we count backwards so we can remove from the list
            for (int i = mediaModels.size() - 1 ; i >= 0; i--) {
                MediaModel media = mediaModels.get(i);
                // ensure correct upload state for media being deleted
                if (checkDeleteState && service.isMediaBeingDeleted(media)) {
                    media.setUploadState(MediaUploadState.DELETING);
                }

                // remove local media that no longer exists
                if (media.getFilePath() != null
                        && org.wordpress.android.util.MediaUtils.isLocalFile(media.getUploadState())) {
                    File file = new File(media.getFilePath());
                    if (!file.exists()) {
                        AppLog.w(AppLog.T.MEDIA, "removing nonexistent local media " + media.getFilePath());
                        // remove from the store
                        mDispatcher.dispatch(MediaActionBuilder.newRemoveMediaAction(media));
                        // remove from the passed list
                        mediaModels.remove(i);
                    }
                }
            }
        }
    }

    List<MediaModel> getFilteredMedia() {
        List<MediaModel> mediaList;
        if (!TextUtils.isEmpty(mSearchTerm)) {
            mediaList = mMediaStore.searchSiteMedia(mSite, mSearchTerm);
        } else if (mBrowserType == MediaBrowserType.EDITOR_PICKER) {
            List<MediaModel> allMedia = mMediaStore.getAllSiteMedia(mSite);
            mediaList = new ArrayList<>();
            for (MediaModel media: allMedia) {
                String mime = media.getMimeType();
                if (mime != null && (mime.startsWith("image") || mime.startsWith("video"))) {
                    mediaList.add(media);
                }
            }
        } else if (mBrowserType == MediaBrowserType.FEATURED_IMAGE_PICKER) {
            mediaList = mMediaStore.getSiteImages(mSite);
        } else {
            switch (mFilter) {
                case FILTER_IMAGES:
                    mediaList = mMediaStore.getSiteImages(mSite);
                    break;
                case FILTER_DOCUMENTS:
                    mediaList = mMediaStore.getSiteDocuments(mSite);
                    break;
                case FILTER_VIDEOS:
                    mediaList = mMediaStore.getSiteVideos(mSite);
                    break;
                case FILTER_AUDIO:
                    mediaList = mMediaStore.getSiteAudio(mSite);
                    break;
                default:
                    mediaList = mMediaStore.getAllSiteMedia(mSite);
                    break;
            }
        }

        ensureCorrectState(mediaList);
        return mediaList;
    }

    void setFilter(@NonNull MediaFilter filter) {
        mFilter  = filter;
        getArguments().putSerializable(MediaBrowserActivity.ARG_FILTER, filter);

        if (!isAdded()) return;

        // temporarily disable animation - otherwise the user will see items animate
        // when they change the filter
        mRecycler.setItemAnimator(null);
        getAdapter().setMediaList(getFilteredMedia());
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                mRecycler.setItemAnimator(new DefaultItemAnimator());
            }
        }, 500L);

        if (mEmptyViewMessageType == EmptyViewMessageType.LOADING) {
            updateEmptyView(EmptyViewMessageType.NO_CONTENT);
        } else {
            updateEmptyView(mEmptyViewMessageType);
        }

        boolean hasFetchedThisFilter = mFetchedFilters[filter.value];
        if (!hasFetchedThisFilter && NetworkUtils.isNetworkAvailable(getActivity())) {
            if (isEmpty()) {
                mSwipeToRefreshHelper.setRefreshing(true);
            }
            fetchMediaList(false);
        }
    }

    @Override
    public void onAdapterFetchMoreData() {
        boolean hasFetchedAll = mFetchedAllFilters[mFilter.value];
        if (!hasFetchedAll) {
            fetchMediaList(true);
        }
    }

    @Override
    public void onAdapterItemClicked(View sourceView, int position, boolean isLongPress) {
        int localMediaId = getAdapter().getLocalMediaIdAtPosition(position);
        mListener.onMediaItemSelected(sourceView, localMediaId, isLongPress);
    }

    @Override
    public void onAdapterSelectionCountChanged(int count) {
        if (mBrowserType == MediaBrowserType.FEATURED_IMAGE_PICKER) {
            return;
        }

        if (count == 0 && mActionMode != null) {
            mActionMode.finish();
        } else if (mActionMode == null) {
            ((AppCompatActivity) getActivity()).startSupportActionMode(new ActionModeCallback());
        }

        updateActionModeTitle(count);
    }

    @Override
    public void onAdapterRequestRetry(int position) {
        int localMediaId = getAdapter().getLocalMediaIdAtPosition(position);
        mListener.onMediaRequestRetry(localMediaId);
    }

    @Override
    public void onAdapterRequestDelete(int position) {
        int localMediaId = getAdapter().getLocalMediaIdAtPosition(position);
        mListener.onMediaRequestDelete(localMediaId);
    }

    @SuppressWarnings("unused")
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onMediaListFetched(OnMediaListFetched event) {
        if (event.isError()) {
            handleFetchAllMediaError(event);
            return;
        }

        handleFetchAllMediaSuccess(event);
    }

    /*
     * load the adapter from the local store
     */
    void reload() {
        if (isAdded()) {
            getAdapter().setMediaList(getFilteredMedia());
        }
    }

    /*
     * update just the passed media item - if it doesn't exist it may be because
     * it was just added, so reload the adapter
     */
    void updateMediaItem(@NonNull MediaModel media, boolean forceUpdate) {
        if (!isAdded() || !hasAdapter()) return;

        if (getAdapter().mediaExists(media)) {
            getAdapter().updateMediaItem(media, forceUpdate);
        } else {
            reload();
        }
    }

    void removeMediaItem(@NonNull MediaModel media) {
        if (!isAdded() || !hasAdapter()) return;

        getAdapter().removeMediaItem(media);
    }

    public void search(String searchTerm) {
        mSearchTerm = searchTerm;
        List<MediaModel> mediaList = mMediaStore.searchSiteMedia(mSite, mSearchTerm);
        mGridAdapter.setMediaList(mediaList);
    }

    public void clearSelection() {
        getAdapter().clearSelection();
    }

    public void removeFromMultiSelect(int localMediaId) {
        if (hasAdapter()
                && getAdapter().isInMultiSelect()
                && getAdapter().isItemSelected(localMediaId)) {
            getAdapter().removeSelectionByLocalId(localMediaId);
        }
    }

    private void setRefreshing(boolean isRefreshing) {
        mIsRefreshing = isRefreshing;
        if (!isRefreshing) {
            mSwipeToRefreshHelper.setRefreshing(false);
        }
    }

    private void setSwipeToRefreshEnabled(boolean enabled) {
        if (isAdded()) {
            mSwipeToRefreshHelper.setEnabled(enabled);
        }
    }

    private void updateEmptyView(EmptyViewMessageType emptyViewMessageType) {
        mEmptyViewMessageType = emptyViewMessageType;

        if (!isAdded() || mEmptyView == null) return;

        if (isEmpty()) {
            int stringId;
            switch (emptyViewMessageType) {
                case LOADING:
                    stringId = R.string.media_fetching;
                    break;
                case NO_CONTENT:
                    switch (mFilter) {
                        case FILTER_IMAGES:
                            stringId = R.string.media_empty_image_list;
                            break;
                        case FILTER_VIDEOS:
                            stringId = R.string.media_empty_videos_list;
                            break;
                        case FILTER_DOCUMENTS:
                            stringId = R.string.media_empty_documents_list;
                            break;
                        case FILTER_AUDIO:
                            stringId = R.string.media_empty_audio_list;
                            break;
                        default:
                            stringId = R.string.media_empty_list;
                            break;
                    }
                    break;
                case NETWORK_ERROR:
                    stringId = R.string.no_network_message;
                    break;
                case PERMISSION_ERROR:
                    stringId = R.string.media_error_no_permission;
                    break;
                default:
                    stringId = R.string.error_refresh_media;
                    break;
            }

            mEmptyView.setText(getText(stringId));
            mEmptyView.setVisibility(View.VISIBLE);
        } else {
            mEmptyView.setVisibility(View.GONE);
        }
    }

    private void hideEmptyView() {
        if (isAdded() && mEmptyView != null) {
            mEmptyView.setVisibility(View.GONE);
        }
    }

    private void saveState(Bundle outState) {
        outState.putIntArray(BUNDLE_SELECTED_STATES, ListUtils.toIntArray(getAdapter().getSelectedItems()));
        outState.putInt(BUNDLE_SCROLL_POSITION, mGridManager.findFirstCompletelyVisibleItemPosition());
        outState.putBoolean(BUNDLE_IN_MULTI_SELECT_MODE, getAdapter().isInMultiSelect());
        outState.putString(BUNDLE_EMPTY_VIEW_MESSAGE, mEmptyViewMessageType.name());
        outState.putBooleanArray(BUNDLE_FETCHED_FILTERS, mFetchedFilters);
        outState.putBooleanArray(BUNDLE_RETRIEVED_ALL_FILTERS, mFetchedAllFilters);
    }

    private void updateActionModeTitle(int selectCount) {
        if (mActionMode != null) {
            mActionMode.setTitle(String.format(getString(R.string.cab_selected), selectCount));
        }
    }

    private void restoreState(@NonNull Bundle savedInstanceState) {
        boolean isInMultiSelectMode = savedInstanceState.getBoolean(BUNDLE_IN_MULTI_SELECT_MODE);
        if (isInMultiSelectMode) {
            getAdapter().setInMultiSelect(true);
            if (savedInstanceState.containsKey(BUNDLE_SELECTED_STATES)) {
                ArrayList<Integer> selectedItems = ListUtils.fromIntArray(savedInstanceState.getIntArray(BUNDLE_SELECTED_STATES));
                getAdapter().setSelectedItems(selectedItems);
                setSwipeToRefreshEnabled(false);
            }
        }

        mFetchedFilters = savedInstanceState.getBooleanArray(BUNDLE_FETCHED_FILTERS);
        mFetchedAllFilters = savedInstanceState.getBooleanArray(BUNDLE_RETRIEVED_ALL_FILTERS);

        EmptyViewMessageType emptyType = EmptyViewMessageType.getEnumFromString(savedInstanceState.
                getString(BUNDLE_EMPTY_VIEW_MESSAGE));
        updateEmptyView(emptyType);
    }

    private void fetchMediaList(boolean loadMore) {
        // do not refresh if there is no network
        if (!NetworkUtils.isNetworkAvailable(getActivity())) {
            updateEmptyView(EmptyViewMessageType.NETWORK_ERROR);
            setRefreshing(false);
            return;
        }

        // do not refresh if in search
        if (!TextUtils.isEmpty(mSearchTerm)) {
            setRefreshing(false);
            return;
        }

        if (!mIsRefreshing) {
            setRefreshing(true);
            updateEmptyView(EmptyViewMessageType.LOADING);
            if (loadMore) {
                mSwipeToRefreshHelper.setRefreshing(true);
            }

            FetchMediaListPayload payload =
                    new FetchMediaListPayload(mSite, NUM_MEDIA_PER_FETCH, loadMore, mFilter.toMimeType());
            mDispatcher.dispatch(MediaActionBuilder.newFetchMediaListAction(payload));
        }
    }

    private void handleFetchAllMediaSuccess(OnMediaListFetched event) {
        if (!isAdded()) return;

        // make sure this request was for the current filter
        if (!TextUtils.isEmpty(event.mimeType)
                && MediaFilter.fromMimeType(event.mimeType) != mFilter) {
            return;
        }

        List<MediaModel> filteredMedia = getFilteredMedia();
        ensureCorrectState(filteredMedia);
        getAdapter().setMediaList(filteredMedia);

        boolean hasRetrievedAll = !event.canLoadMore;
        getAdapter().setHasRetrievedAll(hasRetrievedAll);

        int position = mFilter.getValue();
        mFetchedFilters[position] = true;

        if (hasRetrievedAll) {
            if (mFilter == MediaFilter.FILTER_ALL) {
                setHasFetchedMediaForAllFilters();
            } else {
                mFetchedAllFilters[position] = true;
            }
        }

        setRefreshing(false);
        updateEmptyView(EmptyViewMessageType.NO_CONTENT);
    }

    private void handleFetchAllMediaError(OnMediaListFetched event) {
        MediaErrorType errorType = event.error.type;
        AppLog.e(AppLog.T.MEDIA, "Media error occurred: " + errorType);
        if (!isAdded()) return;

        if (!TextUtils.isEmpty(event.mimeType)
                && MediaFilter.fromMimeType(event.mimeType) != mFilter) {
            return;
        }

        int toastResId;
        if (errorType == MediaErrorType.AUTHORIZATION_REQUIRED) {
            updateEmptyView(EmptyViewMessageType.PERMISSION_ERROR);
            toastResId = R.string.media_error_no_permission;
         } else {
            updateEmptyView(EmptyViewMessageType.GENERIC_ERROR);
            toastResId = R.string.error_refresh_media;
        }

        // only show the toast if the list is NOT empty since the empty view shows the same message
        if (!isEmpty()) {
            ToastUtils.showToast(getActivity(), getString(toastResId));
        }

        setRefreshing(false);
        setHasFetchedMediaForAllFilters();
        getAdapter().setHasRetrievedAll(true);
    }

    private void setResultIdsAndFinish() {
        Intent intent = new Intent();
        if (getAdapter().getSelectedItemCount() > 0) {
            ArrayList<Long> remoteMediaIds = new ArrayList<>();
            for (Integer localId : getAdapter().getSelectedItems()) {
                MediaModel media = mMediaStore.getMediaWithLocalId(localId);
                if (media != null) {
                    remoteMediaIds.add(media.getMediaId());
                }
            }
            intent.putExtra(MediaBrowserActivity.RESULT_IDS, ListUtils.toLongArray(remoteMediaIds));
        }
        getActivity().setResult(RESULT_OK, intent);
        getActivity().finish();
    }


    private final class ActionModeCallback implements ActionMode.Callback {
        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            mActionMode = mode;
            int selectCount = getAdapter().getSelectedItemCount();
            MenuInflater inflater = mode.getMenuInflater();
            inflater.inflate(R.menu.media_multiselect, menu);
            setSwipeToRefreshEnabled(false);
            getAdapter().setInMultiSelect(true);
            WPActivityUtils.setStatusBarColor(getActivity().getWindow(), R.color.grey_darken_30);
            updateActionModeTitle(selectCount);
            return true;
        }

        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            MenuItem mnuConfirm = menu.findItem(R.id.mnu_confirm_selection);
            mnuConfirm.setVisible(mBrowserType.isPicker());
            return true;
        }

        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            if (item.getItemId() == R.id.mnu_confirm_selection) {
                setResultIdsAndFinish();
            }
            return true;
        }

        @Override
        public void onDestroyActionMode(ActionMode mode) {
            setSwipeToRefreshEnabled(true);
            getAdapter().setInMultiSelect(false);
            WPActivityUtils.setStatusBarColor(getActivity().getWindow(), R.color.status_bar_tint);
            mActionMode = null;
        }
    }
}
