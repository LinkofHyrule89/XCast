package com.xcast.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.transition.AutoTransition
import androidx.transition.TransitionManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import coil.load
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastState
import com.google.android.gms.cast.framework.IntroductoryOverlay
import com.google.android.gms.cast.MediaTrack
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.color.MaterialColors
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.slider.Slider
import androidx.mediarouter.app.MediaRouteActionProvider
import com.xcast.android.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private var castContext: CastContext? = null
    private var mediaRouteMenuItem: MenuItem? = null
    private var lastChapterSignature: String? = null
    private var lastRecentsSignature: String? = null
    private var lastResumeSavedAt = 0L
    private var introOverlay: IntroductoryOverlay? = null
    private var localPlayer: ExoPlayer? = null
    private lateinit var nowPlayingBehavior: BottomSheetBehavior<LinearLayout>
    private var navInset = 0
    private var userSeeking = false
    private val speeds = listOf(0.75 to "0.75×", 1.0 to "1×", 1.25 to "1.25×", 1.5 to "1.5×", 2.0 to "2×")

    private val progressListener = RemoteMediaClient.ProgressListener { position, duration ->
        highlightCurrentChapter(position)
        updatePlaybackChrome(position, duration)
        val now = System.currentTimeMillis()
        if (now - lastResumeSavedAt > 5_000L) {
            lastResumeSavedAt = now
            viewModel.saveResume(position, duration)
        }
    }

    private val mediaCallback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() {
            showMiniPlayer(isMediaActive())
            updatePlayPauseIcons()
            updateVolumeUi()
            updateRemoteTab()
        }
    }

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) {
            updateConnectedDevice()
            maybeAutoCast()
            render(viewModel.uiState.value)
        }
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            updateConnectedDevice()
            render(viewModel.uiState.value)
        }
        override fun onSessionEnded(session: CastSession, error: Int) {
            updateConnectedDevice()
            showMiniPlayer(false)
            collapseNowPlaying()
            setKeepAwake(false)
            render(viewModel.uiState.value)
            maybeStartLocalPreview()
        }
        override fun onSessionStartFailed(session: CastSession, error: Int) {
            Toast.makeText(this@MainActivity, "Could not start Cast session ($error)", Toast.LENGTH_LONG).show()
        }
        override fun onSessionStarting(session: CastSession) {}
        override fun onSessionEnding(session: CastSession) {}
        override fun onSessionResumeFailed(session: CastSession, error: Int) {}
        override fun onSessionResuming(session: CastSession, sessionId: String) {}
        override fun onSessionSuspended(session: CastSession, reason: Int) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        setupNowPlaying()
        setupBottomNav()
        savedInstanceState?.getInt(STATE_TAB, R.id.tab_cast)?.let { id ->
            if (id != 0) binding.bottomNav.selectedItemId = id
        }
        updateConnectedDevice()
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            navInset = bars.bottom
            binding.appBar.updatePadding(top = bars.top)
            binding.bottomNav.updatePadding(bottom = bars.bottom)
            binding.nowPlayingSheet.updatePadding(bottom = bars.bottom)
            padTabsForChrome()
            insets
        }

        try {
            castContext = CastContext.getSharedInstance(this)
        } catch (e: Exception) {
            Toast.makeText(this, "Google Cast is unavailable on this device.", Toast.LENGTH_LONG).show()
        }

        binding.urlInputLayout.setEndIconOnClickListener { resolveFromInput() }
        binding.urlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                resolveFromInput()
                true
            } else {
                false
            }
        }
        binding.castNowButton.setOnClickListener { castLoadedVideo() }
        binding.skipBackButton.setOnClickListener {
            currentClient()?.let { CastController.skip(it, -10_000) }
        }
        binding.skipForwardButton.setOnClickListener {
            currentClient()?.let { CastController.skip(it, 10_000) }
        }
        binding.npSkipBack.setOnClickListener {
            currentClient()?.let { CastController.skip(it, -10_000) }
        }
        binding.npSkipForward.setOnClickListener {
            currentClient()?.let { CastController.skip(it, 10_000) }
        }
        binding.stopButton.setOnClickListener {
            currentClient()?.stop()
            collapseNowPlaying()
        }
        binding.miniPlayPause.setOnClickListener {
            currentClient()?.let { CastController.togglePlayPause(it) }
        }
        binding.npPlayPause.setOnClickListener {
            currentClient()?.let { CastController.togglePlayPause(it) }
        }
        binding.miniPlayer.setOnClickListener { expandNowPlaying() }
        binding.retryButton.setOnClickListener { viewModel.retryLookup() }
        binding.openInXButton.setOnClickListener { openInX() }
        binding.muteButton.setOnClickListener {
            val session = currentSession() ?: return@setOnClickListener
            CastController.setMuted(session, !session.isMute)
            updateVolumeUi()
        }
        binding.volumeSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            currentSession()?.let { CastController.setVolume(it, value / 100.0) }
        }
        binding.prevChapterButton.setOnClickListener {
            val client = currentClient() ?: return@setOnClickListener
            val chapters = viewModel.uiState.value.video?.chapters.orEmpty()
            CastController.seekTo(client, CastController.prevChapterPosition(chapters, client.approximateStreamPosition))
        }
        binding.nextChapterButton.setOnClickListener {
            val client = currentClient() ?: return@setOnClickListener
            val chapters = viewModel.uiState.value.video?.chapters.orEmpty()
            val dur = client.streamDuration.takeIf { it > 0 } ?: viewModel.uiState.value.video?.durationMs ?: 0L
            CastController.seekTo(client, CastController.nextChapterPosition(chapters, client.approximateStreamPosition, dur))
        }
        setupSpeedDropdown()
        binding.castableOnlySwitch.setOnCheckedChangeListener { _, checked ->
            viewModel.setCastableOnly(checked)
        }
        binding.historyClearButton.setOnClickListener { confirmClearHistory() }
        binding.playbackSettingsToggle.setOnClickListener {
            setPlaybackSettingsOpen(!binding.playbackSettingsPanel.isVisible, animate = true)
        }
        setPlaybackSettingsOpen(savedInstanceState?.getBoolean(STATE_SETTINGS_OPEN) == true, animate = false)
        setupNowPlayingSeek()
        binding.urlInput.doAfterTextChanged { }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { render(it) }
            }
        }

        handleIncoming(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncoming(intent)
    }

    override fun onResume() {
        super.onResume()
        castContext?.sessionManager?.addSessionManagerListener(sessionListener, CastSession::class.java)
        attachRemote()
    }

    override fun onPause() {
        currentClient()?.let { viewModel.saveResume(it.approximateStreamPosition, it.streamDuration) }
        detachRemote()
        castContext?.sessionManager?.removeSessionManagerListener(sessionListener, CastSession::class.java)
        super.onPause()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.cast, menu)
        CastButtonFactory.setUpMediaRouteButton(applicationContext, menu, R.id.media_route_menu_item)
        mediaRouteMenuItem = menu.findItem(R.id.media_route_menu_item)
        maybeShowCastOverlay()
        return true
    }

    override fun onStop() {
        releaseLocalPlayer()
        super.onStop()
    }

    private fun handleIncoming(intent: Intent?) {
        if (intent == null) return
        val shared = when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
                ?: intent.getStringExtra(Intent.EXTRA_SUBJECT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }
        if (!shared.isNullOrBlank() && TweetParser.extractStatusId(shared) != null) {
            binding.urlInput.setText(shared)
            viewModel.resolve(shared)
        }
    }

    private fun render(state: UiState) {
        binding.progress.isVisible = state.loading
        binding.urlInputLayout.isEnabled = !state.loading
        binding.statusText.text = state.message
        binding.castNowButton.isEnabled = state.video != null
        binding.castNowButton.text = when {
            state.video == null -> getString(R.string.cast_to_tv)
            currentSession() == null -> getString(R.string.connect_then_cast)
            else -> getString(R.string.cast_to_tv)
        }
        binding.retryButton.isVisible = state.error == LookupError.LOOKUP_FAILED ||
            state.error == LookupError.REJECTED ||
            state.error == LookupError.NO_VIDEO
        state.video?.let { video ->
            binding.previewTitle.text = video.title
            val duration = video.durationMs?.let { formatPosition(it) }
            binding.previewMeta.text = listOfNotNull(video.subtitle.takeIf { it.isNotBlank() }, duration)
                .joinToString(" · ")
            val thumb = video.thumbnailUrl?.takeIf { SafeUrls.isAllowedHttps(it) }
            if (thumb != null) {
                binding.previewThumb.isVisible = true
                binding.previewThumb.load(thumb)
            } else {
                binding.previewThumb.isVisible = false
            }
        }
        binding.previewCard.isVisible = state.video != null
        binding.openInXButton.isVisible = state.video != null
        binding.chapterSkipRow.isVisible = state.video?.chapters?.isNotEmpty() == true
        bindRecents(state)
        maybeStartLocalPreview()
        setKeepAwake(currentSession() != null)
        if (binding.castableOnlySwitch.isChecked != state.castableOnly) {
            binding.castableOnlySwitch.isChecked = state.castableOnly
        }
        bindDropdowns(state)
        bindChapters(state)
        bindMiniPlayerMetadata()
        updateRemoteTab()
        attachRemote()
        updateConnectedDevice()
        maybeAutoCast()
        padTabsForChrome()
    }

    private fun updateConnectedDevice() {
        val name = currentSession()?.castDevice?.friendlyName
        binding.toolbar.subtitle = name?.let { getString(R.string.connected_to, it) }
    }

    private fun currentSession(): CastSession? = castContext?.sessionManager?.currentCastSession

    private fun currentClient(): RemoteMediaClient? = currentSession()?.remoteMediaClient

    private fun isMediaActive(): Boolean {
        val client = currentClient() ?: return false
        return client.mediaInfo != null || CastController.isPlaying(client)
    }

    private fun openCastDialog() {
        val provider = mediaRouteMenuItem?.actionProvider as? MediaRouteActionProvider
        provider?.onPerformDefaultAction()
    }

    private fun maybeAutoCast() {
        val video = viewModel.uiState.value.video ?: return
        val session = currentSession() ?: return
        val videoTrack = viewModel.selectedVideo() ?: return
        if (!viewModel.consumePendingCast()) return
        val silent = viewModel.consumeSilentRecast()
        val start = viewModel.consumeResume().coerceAtLeast(localPlayer?.currentPosition ?: 0L)
        pauseLocalPlayer()
        CastController.load(
            session,
            video,
            videoTrack,
            viewModel.selectedAudio(),
            viewModel.selectedCaption(),
            startPositionMs = start,
            context = this,
            onSuccess = {
                attachRemote()
                setKeepAwake(true)
                if (!silent && start > 5_000L) {
                    Snackbar.make(binding.root, R.string.resumed_from, Snackbar.LENGTH_LONG)
                        .setAction(R.string.restart_from_start) {
                            currentClient()?.let { CastController.seekTo(it, 0) }
                            viewModel.clearResumeAndRestart()
                        }
                        .show()
                }
            },
            onFailure = { error ->
                viewModel.markRejected(error)
                Toast.makeText(this, error, Toast.LENGTH_LONG).show()
            },
        )
    }

    private fun bindDropdowns(state: UiState) {
        val videos = viewModel.visibleVideoTracks()
        val audios = state.video?.audioTracks.orEmpty()
        bindDropdown(
            view = binding.videoDropdown,
            items = videos.map { it.id to "${it.label}  ·  ${it.detail}" },
            selectedId = state.selectedVideoId,
            onSelect = { id -> applyVideoChange(id) },
        )
        bindDropdown(
            view = binding.audioDropdown,
            items = audios.map { it.id to it.label },
            selectedId = state.selectedAudioId,
            onSelect = viewModel::selectAudio,
        )
        val captions = state.video?.captionTracks.orEmpty()
        binding.captionDropdownLayout.isVisible = captions.size > 1
        if (captions.size > 1) {
            bindDropdown(
                view = binding.captionDropdown,
                items = captions.map { it.id to it.label },
                selectedId = state.selectedCaptionId,
                onSelect = { id ->
                    viewModel.selectCaption(id)
                    applyCaptionChange()
                },
            )
        }
    }

    private fun bindDropdown(
        view: AutoCompleteTextView,
        items: List<Pair<String, String>>,
        selectedId: String?,
        onSelect: (String) -> Unit,
    ) {
        val signature = items.joinToString("|") { it.first } + "#" + selectedId
        if (view.tag == signature) return
        view.tag = signature
        val labels = items.map { it.second }
        view.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, labels),
        )
        val selectedLabel = items.firstOrNull { it.first == selectedId }?.second.orEmpty()
        view.setText(selectedLabel, false)
        view.setOnItemClickListener { _, _, position, _ ->
            items.getOrNull(position)?.first?.let(onSelect)
        }
    }

    private fun applyVideoChange(id: String) {
        if (!viewModel.selectVideo(id)) return
        val session = currentSession() ?: return
        val client = session.remoteMediaClient
        val position = client?.approximateStreamPosition?.takeIf { it > 0L }
            ?: localPlayer?.currentPosition?.takeIf { it > 0L }
            ?: 0L
        val duration = client?.streamDuration?.takeIf { it > 0L }
            ?: viewModel.uiState.value.video?.durationMs
            ?: 0L
        if (position > 0L) viewModel.saveResume(position, duration)
        viewModel.markPendingCast(silent = true)
        maybeAutoCast()
    }

    private fun applyCaptionChange() {
        val session = currentSession() ?: return
        val client = session.remoteMediaClient ?: return
        val hasTextTrack = client.mediaInfo?.mediaTracks.orEmpty().any { it.type == MediaTrack.TYPE_TEXT }
        val caption = viewModel.selectedCaption()
        if (hasTextTrack || caption?.id == TweetVideoResolver.CAPTIONS_OFF) {
            CastController.applyActiveTracks(client, viewModel.selectedAudio(), caption)
        } else {
            viewModel.markPendingCast()
            maybeAutoCast()
        }
    }

    private fun castLoadedVideo() {
        val video = viewModel.uiState.value.video ?: return
        val session = currentSession()
        if (session == null) {
            val state = castContext?.castState
            if (state == CastState.NO_DEVICES_AVAILABLE) {
                Snackbar.make(binding.root, R.string.no_cast_devices, Snackbar.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, R.string.connect_cast_first, Toast.LENGTH_LONG).show()
                openCastDialog()
            }
            viewModel.markPendingCast()
            return
        }
        viewModel.markPendingCast()
        maybeAutoCast()
    }

    private fun attachRemote() {
        val client = currentClient() ?: return
        client.removeProgressListener(progressListener)
        client.addProgressListener(progressListener, 500)
        client.unregisterCallback(mediaCallback)
        client.registerCallback(mediaCallback)
        showMiniPlayer(isMediaActive())
        updateVolumeUi()
        updateRemoteTab()
        highlightCurrentChapter(client.approximateStreamPosition)
        val duration = client.streamDuration.takeIf { it > 0 } ?: viewModel.uiState.value.video?.durationMs ?: 0L
        updatePlaybackChrome(client.approximateStreamPosition, duration)
    }

    private fun detachRemote() {
        currentClient()?.let { client ->
            client.removeProgressListener(progressListener)
            client.unregisterCallback(mediaCallback)
        }
    }

    private fun resolveFromInput() {
        viewModel.resolve(binding.urlInput.text?.toString().orEmpty())
    }

    private fun setupBottomNav() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            showTab(item.itemId)
            true
        }
        showTab(R.id.tab_cast)
    }

    private fun showTab(id: Int) {
        binding.tabCast.isVisible = id == R.id.tab_cast
        binding.tabHistory.isVisible = id == R.id.tab_history
        binding.tabRemote.isVisible = id == R.id.tab_remote
    }

    private fun setupNowPlaying() {
        nowPlayingBehavior = BottomSheetBehavior.from(binding.nowPlayingSheet)
        nowPlayingBehavior.isHideable = true
        nowPlayingBehavior.isFitToContents = true
        nowPlayingBehavior.skipCollapsed = true
        nowPlayingBehavior.state = BottomSheetBehavior.STATE_HIDDEN
        nowPlayingBehavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) {
                val expanded = newState == BottomSheetBehavior.STATE_EXPANDED
                if (currentClient() != null) {
                    binding.miniPlayer.isVisible = !expanded
                }
                padTabsForChrome()
            }
            override fun onSlide(bottomSheet: View, slideOffset: Float) {}
        })
    }

    private fun setupNowPlayingSeek() {
        binding.npSeek.addOnChangeListener { slider, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            val client = currentClient() ?: return@addOnChangeListener
            val duration = client.streamDuration.takeIf { it > 0 } ?: return@addOnChangeListener
            binding.npTime.text =
                "${formatPosition((value / slider.valueTo * duration).toLong())} / ${formatPosition(duration)}"
        }
        binding.npSeek.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                userSeeking = true
            }
            override fun onStopTrackingTouch(slider: Slider) {
                val client = currentClient()
                val duration = client?.streamDuration ?: 0L
                if (client != null && duration > 0) {
                    CastController.seekTo(client, (slider.value / slider.valueTo * duration).toLong())
                }
                userSeeking = false
            }
        })
    }

    private fun expandNowPlaying() {
        if (currentClient() == null) return
        nowPlayingBehavior.state = BottomSheetBehavior.STATE_EXPANDED
    }

    private fun collapseNowPlaying() {
        if (!::nowPlayingBehavior.isInitialized) return
        nowPlayingBehavior.state = BottomSheetBehavior.STATE_HIDDEN
    }

    private fun showMiniPlayer(show: Boolean) {
        val expanded = ::nowPlayingBehavior.isInitialized &&
            nowPlayingBehavior.state == BottomSheetBehavior.STATE_EXPANDED
        binding.miniPlayer.isVisible = show && !expanded
        if (!show) collapseNowPlaying()
        bindMiniPlayerMetadata()
        padTabsForChrome()
    }

    private fun bindMiniPlayerMetadata() {
        val video = viewModel.uiState.value.video ?: return
        binding.miniTitle.text = video.title
        binding.miniSubtitle.text = video.subtitle
        binding.npTitle.text = video.title
        binding.npSubtitle.text = video.subtitle
        val thumb = video.thumbnailUrl?.takeIf { SafeUrls.isAllowedHttps(it) }
        if (thumb != null) {
            binding.miniThumb.load(thumb)
            binding.npArtwork.load(thumb)
        }
    }

    private fun updatePlaybackChrome(position: Long, duration: Long) {
        val dur = duration.takeIf { it > 0 } ?: viewModel.uiState.value.video?.durationMs ?: 0L
        if (dur <= 0L) return
        val progress = ((position * 1000L) / dur).toInt().coerceIn(0, 1000)
        binding.miniProgress.progress = progress
        if (!userSeeking) {
            binding.npSeek.valueTo = 1000f
            binding.npSeek.value = progress.toFloat()
            binding.npTime.text = "${formatPosition(position)} / ${formatPosition(dur)}"
        }
        updatePlayPauseIcons()
    }

    private fun updatePlayPauseIcons() {
        val playing = currentClient()?.let { CastController.isPlaying(it) } == true
        val icon = if (playing) R.drawable.ic_pause else R.drawable.ic_play
        val desc = getString(if (playing) R.string.pause else R.string.play)
        binding.miniPlayPause.setImageResource(icon)
        binding.miniPlayPause.contentDescription = desc
        binding.npPlayPause.setImageResource(icon)
        binding.npPlayPause.contentDescription = desc
    }

    private fun updateRemoteTab() {
        val session = currentSession()
        binding.remoteEmpty.isVisible = session == null
        binding.remoteConnected.isVisible = session != null
        binding.remoteDeviceName.text = session?.castDevice?.friendlyName.orEmpty()
    }

    private fun padTabsForChrome() {
        val pad = binding.bottomStack.height.takeIf { it > 0 }
            ?: (80 * resources.displayMetrics.density).toInt()
        binding.tabCast.updatePadding(bottom = pad)
        binding.tabHistory.updatePadding(bottom = pad)
        binding.tabRemote.updatePadding(bottom = pad)
    }

    private fun bindChapters(state: UiState) {
        val chapters = state.video?.chapters.orEmpty()
        val signature = chapters.joinToString { "${it.positionMs}:${it.title}" }
        binding.chaptersHeader.isVisible = chapters.isNotEmpty()
        binding.chapterList.isVisible = chapters.isNotEmpty()
        if (signature == lastChapterSignature) return
        lastChapterSignature = signature
        binding.chapterList.removeAllViews()
        val inflater = LayoutInflater.from(this)
        chapters.forEach { chapter ->
            val row = inflater.inflate(R.layout.item_chapter, binding.chapterList, false)
            row.tag = chapter.positionMs
            row.findViewById<TextView>(R.id.chapterTime).text = chapter.stamp
            row.findViewById<TextView>(R.id.chapterTitle).text = chapter.title
            row.setOnClickListener {
                val client = currentClient()
                if (client != null) {
                    CastController.seekTo(client, chapter.positionMs)
                } else {
                    Toast.makeText(this, R.string.connect_cast_first, Toast.LENGTH_SHORT).show()
                }
            }
            binding.chapterList.addView(row)
        }
    }

    private fun highlightCurrentChapter(positionMs: Long) {
        val chapters = viewModel.uiState.value.video?.chapters.orEmpty()
        if (chapters.isEmpty()) return
        val current = chapters.lastOrNull { it.positionMs <= positionMs } ?: chapters.first()
        for (i in 0 until binding.chapterList.childCount) {
            val row = binding.chapterList.getChildAt(i)
            val start = row.tag as? Long ?: continue
            val titleView = row.findViewById<TextView>(R.id.chapterTitle)
            val active = start == current.positionMs
            titleView.setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
            val color = MaterialColors.getColor(
                titleView,
                if (active) android.R.attr.colorAccent else android.R.attr.textColorPrimary,
            )
            titleView.setTextColor(color)
        }
    }

    private fun setupSpeedDropdown() {
        val labels = speeds.map { it.second }
        binding.speedDropdown.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, labels))
        binding.speedDropdown.setText("1×", false)
        binding.speedDropdown.setOnItemClickListener { _, _, position, _ ->
            val rate = speeds.getOrNull(position)?.first ?: 1.0
            currentClient()?.let { CastController.setSpeed(it, rate) }
        }
    }

    private fun updateVolumeUi() {
        val session = currentSession() ?: return
        binding.muteButton.text = getString(if (session.isMute) R.string.unmute else R.string.mute)
        binding.volumeSlider.value = (session.volume * 100).toFloat().coerceIn(0f, 100f)
    }

    private fun bindRecents(state: UiState) {
        val items = state.recents
        val signature = items.joinToString { "${it.statusId}:${it.updatedAt}:${it.pinned}" }
        binding.historyEmpty.isVisible = items.isEmpty()
        binding.historyClearButton.isVisible = items.any { !it.pinned }
        if (signature == lastRecentsSignature) return
        lastRecentsSignature = signature
        binding.recentsList.removeAllViews()
        val inflater = LayoutInflater.from(this)
        items.forEach { item ->
            val row = inflater.inflate(R.layout.item_recent, binding.recentsList, false)
            row.findViewById<TextView>(R.id.recentTitle).text = item.title
            val dur = item.durationMs?.let { formatPosition(it) }
            val meta = buildList {
                if (item.pinned) add(getString(R.string.pinned_label))
                item.subtitle.takeIf { it.isNotBlank() }?.let { add(it) }
                if (dur != null) add(dur)
            }.joinToString(" · ")
            row.findViewById<TextView>(R.id.recentMeta).text = meta
            val thumb = row.findViewById<ImageView>(R.id.recentThumb)
            item.thumbnailUrl?.takeIf { SafeUrls.isAllowedHttps(it) }?.let { thumb.load(it) }
            val pin = row.findViewById<ImageButton>(R.id.recentPin)
            pin.setImageResource(if (item.pinned) R.drawable.ic_pin_filled else R.drawable.ic_pin)
            pin.contentDescription = getString(
                if (item.pinned) R.string.unpin_history else R.string.pin_history,
            )
            pin.setOnClickListener { viewModel.togglePin(item.statusId) }
            row.findViewById<ImageButton>(R.id.recentDelete).setOnClickListener {
                confirmRemoveHistory(item)
            }
            row.setOnClickListener {
                viewModel.openRecent(item)
                binding.bottomNav.selectedItemId = R.id.tab_cast
            }
            binding.recentsList.addView(row)
        }
    }

    private fun confirmRemoveHistory(item: RecentItem) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.remove_history_title)
            .setMessage(item.title)
            .setPositiveButton(R.string.delete_history_item) { _, _ ->
                viewModel.removeRecent(item.statusId)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmClearHistory() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.clear_history_title)
            .setMessage(R.string.clear_history_message)
            .setPositiveButton(R.string.clear_history) { _, _ ->
                viewModel.clearUnpinnedRecents()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openInX() {
        val id = viewModel.uiState.value.video?.statusId ?: return
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://x.com/i/status/$id")))
    }

    private fun setKeepAwake(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun maybeShowCastOverlay() {
        val item = mediaRouteMenuItem ?: return
        val state = castContext?.castState ?: return
        if (state == CastState.NO_DEVICES_AVAILABLE) return
        introOverlay?.remove()
        introOverlay = IntroductoryOverlay.Builder(this, item)
            .setTitleText(getString(R.string.cast_intro))
            .setSingleTime()
            .setOnOverlayDismissedListener { introOverlay = null }
            .build()
        introOverlay?.show()
    }

    private fun maybeStartLocalPreview() {
        val track = viewModel.selectedVideo()
        val url = track?.url
        val canPreview = currentSession() == null &&
            track != null &&
            track.muxedAudio &&
            url != null &&
            SafeUrls.isAllowedHttps(url) &&
            url.contains(".mp4")
        if (!canPreview) {
            pauseLocalPlayer()
            binding.localPlayer.isVisible = false
            return
        }
        binding.localPlayer.isVisible = true
        val player = localPlayer ?: ExoPlayer.Builder(this).build().also {
            localPlayer = it
            binding.localPlayer.player = it
        }
        val current = player.currentMediaItem?.localConfiguration?.uri?.toString()
        if (current != url) {
            val pos = player.currentPosition
            player.setMediaItem(MediaItem.fromUri(url!!))
            player.prepare()
            if (pos > 1_000L) player.seekTo(pos)
            player.playWhenReady = false
        }
    }

    private fun pauseLocalPlayer() {
        localPlayer?.pause()
    }

    private fun releaseLocalPlayer() {
        binding.localPlayer.player = null
        localPlayer?.release()
        localPlayer = null
    }

    private fun setPlaybackSettingsOpen(open: Boolean, animate: Boolean) {
        setFoldOpen(
            panel = binding.playbackSettingsPanel,
            toggle = binding.playbackSettingsToggle,
            container = binding.previewCard,
            open = open,
            animate = animate,
            expandDesc = R.string.expand_playback_settings,
            collapseDesc = R.string.collapse_playback_settings,
        )
    }

    private fun setFoldOpen(
        panel: View,
        toggle: MaterialButton,
        container: ViewGroup,
        open: Boolean,
        animate: Boolean,
        expandDesc: Int,
        collapseDesc: Int,
    ) {
        toggle.setIconResource(if (open) R.drawable.ic_expand_less else R.drawable.ic_expand_more)
        toggle.contentDescription = getString(if (open) collapseDesc else expandDesc)
        if (panel.isVisible == open) return
        if (animate) {
            TransitionManager.beginDelayedTransition(
                container,
                AutoTransition().apply { duration = 180 },
            )
        }
        panel.isVisible = open
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_SETTINGS_OPEN, binding.playbackSettingsPanel.isVisible)
        outState.putInt(STATE_TAB, binding.bottomNav.selectedItemId)
    }

    companion object {
        private const val STATE_SETTINGS_OPEN = "playback_settings_open"
        private const val STATE_TAB = "bottom_tab"
    }
}
