/* == This file is part of Tomahawk Player - <http://tomahawk-player.org> ===
 *
 *   Copyright 2012, Christopher Reichert <creichert07@gmail.com>
 *   Copyright 2012, Enno Gottschalk <mrmaffen@googlemail.com>
 *
 *   Tomahawk is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   Tomahawk is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with Tomahawk. If not, see <http://www.gnu.org/licenses/>.
 */
package org.tomahawk.libtomahawk.collection;

import com.google.common.collect.Sets;

import org.tomahawk.libtomahawk.authentication.AuthenticatorManager;
import org.tomahawk.libtomahawk.authentication.AuthenticatorUtils;
import org.tomahawk.libtomahawk.authentication.HatchetAuthenticatorUtils;
import org.tomahawk.libtomahawk.database.DatabaseHelper;
import org.tomahawk.libtomahawk.infosystem.InfoRequestData;
import org.tomahawk.libtomahawk.infosystem.InfoSystem;
import org.tomahawk.libtomahawk.infosystem.QueryParams;
import org.tomahawk.libtomahawk.infosystem.hatchet.HatchetInfoPlugin;
import org.tomahawk.libtomahawk.resolver.Query;
import org.tomahawk.tomahawk_android.R;
import org.tomahawk.tomahawk_android.TomahawkApp;

import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.greenrobot.event.EventBus;

/**
 * This class represents a user's local {@link org.tomahawk.libtomahawk.collection.CollectionManager}
 * of all his {@link org.tomahawk.libtomahawk.collection.Track}s.
 */
public class CollectionManager {

    public static final String TAG = CollectionManager.class.getSimpleName();

    private static class Holder {

        private static final CollectionManager instance = new CollectionManager();

    }

    public static class AddedEvent {

        public Collection mCollection;

    }

    public static class UpdatedEvent {

        public Collection mCollection;

        public String mUpdatedItemId;

    }

    private ConcurrentHashMap<String, Collection> mCollections
            = new ConcurrentHashMap<String, Collection>();

    private HashSet<String> mCorrespondingRequestIds = new HashSet<String>();

    private Set<String> mShowAsDeletedPlaylistMap =
            Sets.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private Set<String> mShowAsCreatedPlaylistMap =
            Sets.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private CollectionManager() {
        EventBus.getDefault().register(this);

        addCollection(new UserCollection());
        addCollection(new HatchetCollection());

        ensureLovedItemsPlaylist();
        fetchAll();
    }

    @SuppressWarnings("unused")
    public void onEventAsync(InfoSystem.OpLogIsEmptiedEvent event) {
        for (Integer requestType : event.mRequestTypes) {
            if (requestType
                    == InfoRequestData.INFOREQUESTDATA_TYPE_SOCIALACTIONS) {
                fetchStarredArtists();
                fetchStarredAlbums();
                fetchLovedItemsPlaylist();
            } else if (requestType
                    == InfoRequestData.INFOREQUESTDATA_TYPE_PLAYBACKLOGENTRIES) {
                HatchetAuthenticatorUtils hatchetAuthUtils =
                        (HatchetAuthenticatorUtils) AuthenticatorManager.getInstance()
                                .getAuthenticatorUtils(TomahawkApp.PLUGINNAME_HATCHET);
                InfoSystem.getInstance()
                        .resolvePlaybackLog(hatchetAuthUtils.getLoggedInUser());
            } else if (requestType
                    == InfoRequestData.INFOREQUESTDATA_TYPE_PLAYLISTS
                    || requestType
                    == InfoRequestData.INFOREQUESTDATA_TYPE_PLAYLISTS_PLAYLISTENTRIES) {
                fetchPlaylists();
            }
        }
    }

    @SuppressWarnings("unused")
    public void onEventAsync(final InfoSystem.ResultsEvent event) {
        if (mCorrespondingRequestIds.contains(event.mInfoRequestData.getRequestId())) {
            mCorrespondingRequestIds.remove(event.mInfoRequestData.getRequestId());
            handleHatchetPlaylistResponse(event.mInfoRequestData);
        }
    }

    @SuppressWarnings("unused")
    public void onEventAsync(HatchetAuthenticatorUtils.UserLoginEvent event) {
        HatchetAuthenticatorUtils hatchetAuthUtils =
                (HatchetAuthenticatorUtils) AuthenticatorManager.getInstance()
                        .getAuthenticatorUtils(TomahawkApp.PLUGINNAME_HATCHET);
        InfoSystem.getInstance().resolvePlaybackLog(hatchetAuthUtils.getLoggedInUser());
        fetchAll();
    }

    public void fetchAll() {
        fetchPlaylists();
        fetchLovedItemsPlaylist();
        fetchStarredAlbums();
        fetchStarredArtists();
    }

    public static CollectionManager getInstance() {
        return Holder.instance;
    }

    public void addCollection(Collection collection) {
        mCollections.put(collection.getId(), collection);
        AddedEvent event = new AddedEvent();
        event.mCollection = collection;
        EventBus.getDefault().post(event);
    }

    public Collection getCollection(String collectionId) {
        return mCollections.get(collectionId);
    }

    public java.util.Collection<Collection> getCollections() {
        return mCollections.values();
    }

    /**
     * Store the PlaybackService's currentPlaylist
     */
    public void setCachedPlaylist(Playlist playlist) {
        DatabaseHelper.getInstance().storePlaylist(playlist);
    }

    /**
     * @return the previously cached {@link Playlist}
     */
    public Playlist getCachedPlaylist() {
        return DatabaseHelper.getInstance().getCachedPlaylist();
    }

    /**
     * Remove or add a lovedItem-query from the LovedItems-Playlist, depending on whether or not it
     * is already a lovedItem
     */
    public void toggleLovedItem(Query query) {
        boolean doSweetSweetLovin = !DatabaseHelper.getInstance().isItemLoved(query);
        Log.d(TAG, "Hatchet sync - " + (doSweetSweetLovin ? "loved" : "unloved") + " track "
                + query.getName() + " by " + query.getArtist().getName() + " on "
                + query.getAlbum().getName());
        DatabaseHelper.getInstance().setLovedItem(query, doSweetSweetLovin);
        UpdatedEvent event = new UpdatedEvent();
        event.mUpdatedItemId = query.getCacheKey();
        EventBus.getDefault().post(event);
        AuthenticatorUtils hatchetAuthUtils = AuthenticatorManager.getInstance()
                .getAuthenticatorUtils(TomahawkApp.PLUGINNAME_HATCHET);
        InfoSystem.getInstance().sendSocialActionPostStruct(hatchetAuthUtils, query,
                HatchetInfoPlugin.HATCHET_SOCIALACTION_TYPE_LOVE, doSweetSweetLovin);
    }

    public void toggleLovedItem(Artist artist) {
        boolean doSweetSweetLovin = !DatabaseHelper.getInstance().isItemLoved(artist);
        Log.d(TAG, "Hatchet sync - " + (doSweetSweetLovin ? "starred" : "unstarred") + " artist "
                + artist.getName());
        DatabaseHelper.getInstance().setLovedItem(artist, doSweetSweetLovin);
        UpdatedEvent event = new UpdatedEvent();
        event.mUpdatedItemId = artist.getCacheKey();
        EventBus.getDefault().post(event);
        AuthenticatorUtils hatchetAuthUtils = AuthenticatorManager.getInstance()
                .getAuthenticatorUtils(TomahawkApp.PLUGINNAME_HATCHET);
        InfoSystem.getInstance().sendSocialActionPostStruct(hatchetAuthUtils, artist,
                HatchetInfoPlugin.HATCHET_SOCIALACTION_TYPE_LOVE, doSweetSweetLovin);
    }

    public void toggleLovedItem(Album album) {
        boolean doSweetSweetLovin = !DatabaseHelper.getInstance().isItemLoved(album);
        Log.d(TAG, "Hatchet sync - " + (doSweetSweetLovin ? "starred" : "unstarred") + " album "
                + album.getName() + " by " + album.getArtist().getName());
        DatabaseHelper.getInstance().setLovedItem(album, doSweetSweetLovin);
        UpdatedEvent event = new UpdatedEvent();
        event.mUpdatedItemId = album.getCacheKey();
        EventBus.getDefault().post(event);
        AuthenticatorUtils hatchetAuthUtils = AuthenticatorManager.getInstance()
                .getAuthenticatorUtils(TomahawkApp.PLUGINNAME_HATCHET);
        InfoSystem.getInstance().sendSocialActionPostStruct(hatchetAuthUtils, album,
                HatchetInfoPlugin.HATCHET_SOCIALACTION_TYPE_LOVE, doSweetSweetLovin);
    }

    /**
     * Update the loved items user-playlist and the contained queries.
     */
    private void ensureLovedItemsPlaylist() {
        Playlist lovedItemsPlayList =
                DatabaseHelper.getInstance().getPlaylist(DatabaseHelper.LOVEDITEMS_PLAYLIST_ID);
        if (lovedItemsPlayList == null) {
            // If we don't yet have a Playlist to store loved items, we create and store an
            // empty Playlist here
            Playlist playlist = Playlist.fromQueryList(DatabaseHelper.LOVEDITEMS_PLAYLIST_NAME,
                    new ArrayList<Query>());
            playlist.setId(DatabaseHelper.LOVEDITEMS_PLAYLIST_ID);
            DatabaseHelper.getInstance().storePlaylist(playlist);
        }
    }

    /**
     * Fetch the lovedItems Playlist from the Hatchet API and store it in the local db, if the log
     * of pending operations is empty. Meaning if every love/unlove has already been delivered to
     * the API.
     */
    public void fetchLovedItemsPlaylist() {
        HatchetAuthenticatorUtils hatchetAuthUtils = (HatchetAuthenticatorUtils)
                AuthenticatorManager.getInstance().getAuthenticatorUtils(
                        TomahawkApp.PLUGINNAME_HATCHET);
        if (DatabaseHelper.getInstance().getLoggedOpsCount() == 0) {
            Log.d(TAG, "Hatchet sync - fetching loved tracks");
            mCorrespondingRequestIds.add(InfoSystem.getInstance().resolveFavorites(
                    hatchetAuthUtils.getLoggedInUser()));
        } else {
            Log.d(TAG, "Hatchet sync - sending logged ops before fetching loved tracks");
            InfoSystem.getInstance().sendLoggedOps(hatchetAuthUtils);
        }
    }

    /**
     * Fetch the starred artists from the Hatchet API and store it in the local db, if the log of
     * pending operations is empty. Meaning if every love/unlove has already been delivered to the
     * API.
     */
    public void fetchStarredArtists() {
        HatchetAuthenticatorUtils hatchetAuthUtils = (HatchetAuthenticatorUtils)
                AuthenticatorManager.getInstance().getAuthenticatorUtils(
                        TomahawkApp.PLUGINNAME_HATCHET);
        if (DatabaseHelper.getInstance().getLoggedOpsCount() == 0) {
            Log.d(TAG, "Hatchet sync - fetching starred artists");
            mCorrespondingRequestIds.add(InfoSystem.getInstance().
                    resolveStarredArtists(hatchetAuthUtils.getLoggedInUser()));
        } else {
            Log.d(TAG, "Hatchet sync - sending logged ops before fetching starred artists");
            InfoSystem.getInstance().sendLoggedOps(hatchetAuthUtils);
        }
    }

    /**
     * Fetch the starred albums from the Hatchet API and store it in the local db, if the log of
     * pending operations is empty. Meaning if every love/unlove has already been delivered to the
     * API.
     */
    public void fetchStarredAlbums() {
        HatchetAuthenticatorUtils hatchetAuthUtils = (HatchetAuthenticatorUtils)
                AuthenticatorManager.getInstance().getAuthenticatorUtils(
                        TomahawkApp.PLUGINNAME_HATCHET);
        if (DatabaseHelper.getInstance().getLoggedOpsCount() == 0) {
            Log.d(TAG, "Hatchet sync - fetching starred albums");
            mCorrespondingRequestIds.add(InfoSystem.getInstance()
                    .resolveStarredAlbums(hatchetAuthUtils.getLoggedInUser()));
        } else {
            Log.d(TAG, "Hatchet sync - sending logged ops before fetching starred albums");
            InfoSystem.getInstance().sendLoggedOps(hatchetAuthUtils);
        }
    }

    /**
     * Fetch the Playlists from the Hatchet API and store it in the local db.
     */
    public void fetchPlaylists() {
        HatchetAuthenticatorUtils hatchetAuthUtils = (HatchetAuthenticatorUtils)
                AuthenticatorManager.getInstance().getAuthenticatorUtils(
                        TomahawkApp.PLUGINNAME_HATCHET);
        if (DatabaseHelper.getInstance().getLoggedOpsCount() == 0) {
            Log.d(TAG, "Hatchet sync - fetching playlists");
            mCorrespondingRequestIds.add(InfoSystem.getInstance()
                    .resolvePlaylists(hatchetAuthUtils.getLoggedInUser()));
        } else {
            Log.d(TAG, "Hatchet sync - sending logged ops before fetching playlists");
            InfoSystem.getInstance().sendLoggedOps(hatchetAuthUtils);
        }
    }

    /**
     * Fetch the Playlist entries from the Hatchet API and store them in the local db.
     */
    public void fetchHatchetPlaylistEntries(String playlistId) {
        String hatchetId = DatabaseHelper.getInstance().getPlaylistHatchetId(playlistId);
        String name = DatabaseHelper.getInstance().getPlaylistName(playlistId);
        if (DatabaseHelper.getInstance().getLoggedOpsCount() == 0) {
            if (hatchetId != null) {
                Log.d(TAG, "Hatchet sync - fetching entry list for playlist \"" + name
                        + "\", hatchetId: " + hatchetId);
                QueryParams params = new QueryParams();
                params.playlist_local_id = playlistId;
                params.playlist_id = hatchetId;
                String requestid = InfoSystem.getInstance().resolve(
                        InfoRequestData.INFOREQUESTDATA_TYPE_PLAYLISTS, params);
                mCorrespondingRequestIds.add(requestid);
            } else {
                Log.d(TAG, "Hatchet sync - couldn't fetch entry list for playlist \""
                        + name + "\" because hatchetId was null");
            }
        } else {
            Log.d(TAG, "Hatchet sync - sending logged ops before fetching entry list for playlist"
                    + " \"" + name + "\", hatchetId: " + hatchetId);
            AuthenticatorUtils hatchetAuthUtils = AuthenticatorManager.getInstance()
                    .getAuthenticatorUtils(TomahawkApp.PLUGINNAME_HATCHET);
            InfoSystem.getInstance().sendLoggedOps(hatchetAuthUtils);
        }
    }

    public void handleHatchetPlaylistResponse(InfoRequestData data) {
        if (data.getType() == InfoRequestData.INFOREQUESTDATA_TYPE_USERS_PLAYLISTS) {
            ArrayList<Playlist> storedLists = DatabaseHelper.getInstance().getPlaylists();
            HashMap<String, Playlist> storedListsMap = new HashMap<String, Playlist>();
            for (Playlist storedList : storedLists) {
                if (storedListsMap.containsKey(storedList.getHatchetId())) {
                    Log.e(TAG, "Hatchet sync - playlist \"" + storedList.getName()
                            + "\" is duplicated ... deleting");
                    DatabaseHelper.getInstance().deletePlaylist(storedList.getId());
                } else {
                    storedListsMap.put(storedList.getHatchetId(), storedList);
                }
            }
            List<Playlist> fetchedLists = data.getResultList(Playlist.class);
            Log.d(TAG, "Hatchet sync - playlist count in database: " + storedLists.size()
                    + ", playlist count on Hatchet: " + fetchedLists.size());
            for (final Playlist fetchedList : fetchedLists) {
                Playlist storedList = storedListsMap.remove(fetchedList.getHatchetId());
                if (storedList == null) {
                    if (mShowAsDeletedPlaylistMap.contains(fetchedList.getHatchetId())) {
                        Log.d(TAG, "Hatchet sync - playlist \"" + fetchedList.getName()
                                + "\" didn't exist in database, but was marked as showAsDeleted so"
                                + " we don't store it.");
                    } else {
                        if (mShowAsCreatedPlaylistMap.contains(fetchedList.getHatchetId())) {
                            mShowAsCreatedPlaylistMap.remove(fetchedList.getHatchetId());
                            Log.d(TAG, "Hatchet sync - playlist \"" + fetchedList.getName()
                                    + "\" is no longer marked as showAsCreated, since it seems to "
                                    + "have arrived on the server");
                        }
                        Log.d(TAG, "Hatchet sync - playlist \"" + fetchedList.getName()
                                + "\" didn't exist in database ... storing and fetching entries");
                        // Delete the current revision since we don't want to store it until we have
                        // fetched and added the playlist's entries
                        fetchedList.setCurrentRevision("");
                        DatabaseHelper.getInstance().storePlaylist(fetchedList,
                                new Runnable() {
                                    @Override
                                    public void run() {
                                        fetchHatchetPlaylistEntries(fetchedList.getId());
                                    }
                                });
                    }
                } else if (!storedList.getCurrentRevision()
                        .equals(fetchedList.getCurrentRevision())) {
                    Log.d(TAG, "Hatchet sync - revision differed for playlist \""
                            + fetchedList.getName() + "\" ... fetching entries");
                    fetchHatchetPlaylistEntries(storedList.getId());
                } else if (!storedList.getName().equals(fetchedList.getName())) {
                    Log.d(TAG, "Hatchet sync - title differed for playlist \""
                            + storedList.getName() + "\", new name: \"" + fetchedList.getName()
                            + "\" ... renaming");
                    DatabaseHelper.getInstance().renamePlaylist(storedList, fetchedList.getName());
                }
            }
            for (Playlist storedList : storedListsMap.values()) {
                if (!mShowAsCreatedPlaylistMap.contains(storedList.getHatchetId())) {
                    Log.d(TAG, "Hatchet sync - playlist \"" + storedList.getName()
                            + "\" doesn't exist on Hatchet ... deleting");
                    DatabaseHelper.getInstance().deletePlaylist(storedList.getId());
                } else {
                    Log.d(TAG, "Hatchet sync - playlist \"" + storedList.getName()
                            + "\" doesn't exist on Hatchet, but we don't delete it since it's"
                            + " marked as showAsCreated");
                }
            }
        } else if (data.getType() == InfoRequestData.INFOREQUESTDATA_TYPE_PLAYLISTS) {
            if (data.getHttpType() == InfoRequestData.HTTPTYPE_GET) {
                Playlist filledList = data.getResult(Playlist.class);
                if (filledList != null) {
                    Log.d(TAG, "Hatchet sync - received entry list for playlist \""
                            + filledList.getName() + "\", hatchetId: " + filledList.getHatchetId()
                            + ", count: " + filledList.getEntries().size());
                    DatabaseHelper.getInstance().storePlaylist(filledList);
                }
            } else if (data.getHttpType() == InfoRequestData.HTTPTYPE_POST) {
                String hatchetId = DatabaseHelper.getInstance()
                        .getPlaylistHatchetId(data.getQueryParams().playlist_local_id);
                if (hatchetId != null) {
                    mShowAsCreatedPlaylistMap.add(hatchetId);
                    Log.d(TAG, "Hatchet sync - created playlist and marked as showAsCreated, id: "
                            + data.getQueryParams().playlist_local_id + ", hatchetId: "
                            + hatchetId);
                }
            }
        } else if (data.getType() == InfoRequestData.INFOREQUESTDATA_TYPE_USERS_LOVEDITEMS) {
            Playlist fetchedList = data.getResult(Playlist.class);
            if (fetchedList != null) {
                HatchetAuthenticatorUtils hatchetAuthUtils =
                        (HatchetAuthenticatorUtils) AuthenticatorManager.getInstance()
                                .getAuthenticatorUtils(TomahawkApp.PLUGINNAME_HATCHET);
                String userName = hatchetAuthUtils.getUserName();
                fetchedList.setName(userName + TomahawkApp.getContext()
                        .getString(R.string.users_favorites_suffix));
                fetchedList.setId(DatabaseHelper.LOVEDITEMS_PLAYLIST_ID);
                Log.d(TAG, "Hatchet sync - received list of loved tracks, count: "
                        + fetchedList.getEntries().size());
                DatabaseHelper.getInstance().storePlaylist(fetchedList, true, null);
            }
        } else if (data.getType()
                == InfoRequestData.INFOREQUESTDATA_TYPE_RELATIONSHIPS_USERS_STARREDALBUMS) {
            List<Album> fetchedAlbums = data.getResultList(Album.class);
            Log.d(TAG, "Hatchet sync - received list of starred albums, count: "
                    + fetchedAlbums.size());
            DatabaseHelper.getInstance().storeStarredAlbums(fetchedAlbums);
        } else if (data.getType()
                == InfoRequestData.INFOREQUESTDATA_TYPE_RELATIONSHIPS_USERS_STARREDARTISTS) {
            List<Artist> fetchedArtists = data.getResultList(Artist.class);
            Log.d(TAG, "Hatchet sync - received list of starred artists, count: "
                    + fetchedArtists.size());
            DatabaseHelper.getInstance().storeStarredArtists(fetchedArtists);
        }
    }

    public void deletePlaylist(String playlistId) {
        String playlistName = DatabaseHelper.getInstance().getPlaylistName(playlistId);
        if (playlistName != null) {
            Log.d(TAG, "Hatchet sync - deleting playlist \"" + playlistName + "\", id: "
                    + playlistId);
            Playlist playlist = DatabaseHelper.getInstance().getEmptyPlaylist(playlistId);
            if (playlist.getHatchetId() != null) {
                mShowAsDeletedPlaylistMap.add(playlist.getHatchetId());
            }
            AuthenticatorUtils hatchetAuthUtils = AuthenticatorManager.getInstance()
                    .getAuthenticatorUtils(TomahawkApp.PLUGINNAME_HATCHET);
            InfoSystem.getInstance().deletePlaylist(hatchetAuthUtils, playlistId);
            DatabaseHelper.getInstance().deletePlaylist(playlistId);
        } else {
            Log.e(TAG, "Hatchet sync - couldn't delete playlist with id: " + playlistId);
        }
    }

    public void createPlaylist(Playlist playlist) {
        Log.d(TAG, "Hatchet sync - creating playlist \"" + playlist.getName() + "\", id: "
                + playlist.getId() + " with " + playlist.getEntries().size() + " entries");
        DatabaseHelper.getInstance().storePlaylist(playlist);
        updateTopArtists(playlist.getId());
        AuthenticatorUtils hatchetAuthUtils = AuthenticatorManager.getInstance()
                .getAuthenticatorUtils(TomahawkApp.PLUGINNAME_HATCHET);
        List<String> requestIds = InfoSystem.getInstance()
                .sendPlaylistPostStruct(hatchetAuthUtils, playlist.getId(), playlist.getName());
        if (requestIds != null) {
            mCorrespondingRequestIds.addAll(requestIds);
        }
        for (PlaylistEntry entry : playlist.getEntries()) {
            InfoSystem.getInstance().sendPlaylistEntriesPostStruct(hatchetAuthUtils,
                    playlist.getId(), entry.getName(), entry.getArtist().getName(),
                    entry.getAlbum().getName());
        }
    }

    public void addPlaylistEntries(String playlistId, ArrayList<PlaylistEntry> entries) {
        String playlistName = DatabaseHelper.getInstance().getPlaylistName(playlistId);
        if (playlistName != null) {
            Log.d(TAG, "Hatchet sync - adding " + entries.size() + " entries to \""
                    + playlistName + "\", id: " + playlistId);
            updateTopArtists(playlistId);
            DatabaseHelper.getInstance().addEntriesToPlaylist(playlistId, entries);
            AuthenticatorUtils hatchetAuthUtils = AuthenticatorManager.getInstance()
                    .getAuthenticatorUtils(TomahawkApp.PLUGINNAME_HATCHET);
            for (PlaylistEntry entry : entries) {
                InfoSystem.getInstance().sendPlaylistEntriesPostStruct(hatchetAuthUtils, playlistId,
                        entry.getName(), entry.getArtist().getName(), entry.getAlbum().getName());
            }
        } else {
            Log.e(TAG, "Hatchet sync - couldn't add " + entries.size()
                    + " entries to playlist with id: " + playlistId);
        }
    }

    public void deletePlaylistEntry(String playlistId, String entryId) {
        String playlistName = DatabaseHelper.getInstance().getPlaylistName(playlistId);
        if (playlistName != null) {
            Log.d(TAG, "Hatchet sync - deleting playlist entry in \"" + playlistName
                    + "\", playlistId: " + playlistId + ", entryId: " + entryId);
            updateTopArtists(playlistId);
            DatabaseHelper.getInstance().deleteEntryInPlaylist(playlistId, entryId);
            AuthenticatorUtils hatchetAuthUtils = AuthenticatorManager.getInstance()
                    .getAuthenticatorUtils(TomahawkApp.PLUGINNAME_HATCHET);
            InfoSystem.getInstance().deletePlaylistEntry(hatchetAuthUtils, playlistId, entryId);
        } else {
            Log.e(TAG, "Hatchet sync - couldn't delete entry in playlist, playlistId: "
                    + playlistId + ", entryId: " + entryId);
        }
    }

    public List<Collection> getAvailableCollections(Album album) {
        List<Collection> collections = new ArrayList<Collection>();
        for (Collection collection : mCollections.values()) {
            if (TomahawkApp.PLUGINNAME_HATCHET.equals(collection.getId())
                    || collection.getAlbumTracks(album, false).size() > 0) {
                collections.add(collection);
            }
        }
        return collections;
    }

    public List<Collection> getAvailableCollections(Artist artist) {
        List<Collection> collections = new ArrayList<Collection>();
        for (Collection collection : mCollections.values()) {
            if (TomahawkApp.PLUGINNAME_HATCHET.equals(collection.getId())
                    || collection.getArtistAlbums(artist, false).size() > 0) {
                collections.add(collection);
            }
        }
        return collections;
    }

    private void updateTopArtists(String playlistId) {
        Playlist playlist = DatabaseHelper.getInstance().getPlaylist(playlistId);
        if (playlist != null && playlist.getEntries().size() > 0) {
            playlist.updateTopArtistNames();
            DatabaseHelper.getInstance().updatePlaylist(playlist);
        }
    }
}
