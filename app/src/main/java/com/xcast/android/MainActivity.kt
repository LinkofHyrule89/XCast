package com.xcast.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.ImageView
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
    private var userSeeking = false
    private var lastChapterSignature: String? = null
    private var lastRecentsSignature: String? = null
    private var lastResumeSavedAt = 0L
    private var introOverlay: IntroductoryOverlay? = null
    private var localPlayer: ExoPlayer? = null
    private val speeds = listOf(0.75 to "0.75×", 1.0 to "1×", 1.25 to "1.25×", 1.5 to "1.5×", 2.0 to "2×")

    private val progressListener = RemoteMediaClient.ProgressListener { position, duration ->
        if (!userSeeking) updateSeekUi(position, duration)
        updatePlayPauseLabel()
        val now = System.currentTimeMillis()
        if (now - lastResumeSavedAt > 5_000L) {
            lastResumeSavedAt = now
            viewModel.saveResume(position, duration)
        }
    }

    private val mediaCallback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() {
            updatePlayPauseLabel()
            binding.remoteCard.isVisible = currentClient() != null
        }
    }

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) {
            maybeAutoCast()
            render(viewModel.uiState.value)
        }
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            render(viewModel.uiState.value)
        }
        override fun onSessionEnded(session: CastSession, error: Int) {
            binding.remoteCard.isVisible = false
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
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.appBar.updatePadding(top = bars.top)
            binding.miniControllerHost.updatePadding(bottom = bars.bottom)
            insets
        }

        try {
            castContext = CastContext.getSharedInstance(this)
        } catch (e: Exception) {
            Toast.makeText(this, "Google Cast is unavailable on this device.", Toast.LENGTH_LONG).show()
        }

        binding.resolveButton.setOnClickListener {
            viewModel.resolve(binding.urlInput.text?.toString().orEmpty())
        }
        binding.castNowButton.setOnClickListener { castLoadedVideo() }
        binding.playPauseButton.setOnClickListener {
            currentClient()?.let { CastController.togglePlayPause(it) }
        }
        binding.skipBackButton.setOnClickListener {
            currentClient()?.let { CastController.skip(it, -10_000) }
        }
        binding.skipForwardButton.setOnClickListener {
            currentClient()?.let { CastController.skip(it, 10_000) }
        }
        binding.stopButton.setOnClickListener {
            currentClient()?.stop()
        }
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
        binding.remoteSeek.addOnChangeListener { slider, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            val client = currentClient() ?: return@addOnChangeListener
            val duration = client.streamDuration.takeIf { it > 0 } ?: return@addOnChangeListener
            binding.remoteTime.text =
                "${formatPosition((value / slider.valueTo * duration).toLong())} / ${formatPosition(duration)}"
        }
        binding.remoteSeek.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
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
        binding.castableOnlySwitch.setOnCheckedChangeListener { _, checked ->
            viewModel.setCastableOnly(checked)
        }
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
        binding.resolveButton.isEnabled = !state.loading
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
            binding.previewSubtitle.text = video.subtitle
            binding.previewDuration.isVisible = video.durationMs != null
            binding.previewDuration.text = video.durationMs?.let { formatPosition(it) }.orEmpty()
            val thumb = video.thumbnailUrl?.takeIf { SafeUrls.isAllowedHttps(it) }
            if (thumb != null) {
                binding.previewThumb.isVisible = currentSession() == null
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
        val videoTrack = viewModel.selectedVideo()
        val audioTrack = viewModel.selectedAudio()
        val captionTrack = viewModel.selectedCaption()
        binding.previewUrl.text = listOfNotNull(
            videoTrack?.let { "${it.label}\n${it.url}" },
            audioTrack?.let { "Audio: ${it.label}" },
            captionTrack?.let { "Captions: ${it.label}" },
        ).joinToString("\n")
        bindChapters(state)
        attachRemote()
        maybeAutoCast()
    }

    private fun currentSession(): CastSession? = castContext?.sessionManager?.currentCastSession

    private fun currentClient(): RemoteMediaClient? = currentSession()?.remoteMediaClient

    private fun openCastDialog() {
        val provider = mediaRouteMenuItem?.actionProvider as? MediaRouteActionProvider
        provider?.onPerformDefaultAction()
    }

    private fun maybeAutoCast() {
        val video = viewModel.uiState.value.video ?: return
        val session = currentSession() ?: return
        val videoTrack = viewModel.selectedVideo() ?: return
        if (!viewModel.consumePendingCast()) return
        val start = viewModel.consumeResume().coerceAtLeast(localPlayer?.currentPosition ?: 0L)
        pauseLocalPlayer()
        CastController.load(
            session,
            video,
            videoTrack,
            viewModel.selectedAudio(),
            viewModel.selectedCaption(),
            startPositionMs = start,
            onSuccess = {
                attachRemote()
                binding.remoteCard.isVisible = true
                setKeepAwake(true)
                if (start > 5_000L) {
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
            onSelect = viewModel::selectVideo,
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
        binding.remoteCard.isVisible = true
        updatePlayPauseLabel()
        updateSeekUi(client.approximateStreamPosition, client.streamDuration)
        updateVolumeUi()
    }

    private fun detachRemote() {
        currentClient()?.let { client ->
            client.removeProgressListener(progressListener)
            client.unregisterCallback(mediaCallback)
        }
    }

    private fun updateSeekUi(position: Long, duration: Long) {
        val dur = duration.takeIf { it > 0 } ?: viewModel.uiState.value.video?.durationMs ?: 0L
        binding.remoteTime.text = "${formatPosition(position)} / ${formatPosition(dur)}"
        if (dur > 0 && !userSeeking) {
            binding.remoteSeek.valueTo = 1000f
            binding.remoteSeek.value = ((position * 1000L) / dur).toFloat().coerceIn(0f, 1000f)
        }
        highlightCurrentChapter(position)
    }

    private fun updatePlayPauseLabel() {
        val playing = currentClient()?.let { CastController.isPlaying(it) } == true
        binding.playPauseButton.text = getString(if (playing) R.string.pause else R.string.play)
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
        val signature = items.joinToString { it.statusId + it.updatedAt }
        binding.recentsHeader.isVisible = items.isNotEmpty()
        binding.recentsList.isVisible = items.isNotEmpty()
        if (signature == lastRecentsSignature) return
        lastRecentsSignature = signature
        binding.recentsList.removeAllViews()
        val inflater = LayoutInflater.from(this)
        items.forEach { item ->
            val row = inflater.inflate(R.layout.item_recent, binding.recentsList, false)
            row.findViewById<TextView>(R.id.recentTitle).text = item.title
            val dur = item.durationMs?.let { formatPosition(it) }
            row.findViewById<TextView>(R.id.recentMeta).text = listOfNotNull(item.subtitle, dur).joinToString(" · ")
            val thumb = row.findViewById<ImageView>(R.id.recentThumb)
            item.thumbnailUrl?.takeIf { SafeUrls.isAllowedHttps(it) }?.let { thumb.load(it) }
            row.setOnClickListener { viewModel.openRecent(item) }
            row.setOnLongClickListener {
                viewModel.removeRecent(item.statusId)
                true
            }
            binding.recentsList.addView(row)
        }
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
        binding.previewThumb.isVisible = false
        binding.localPlayer.isVisible = true
        val player = localPlayer ?: ExoPlayer.Builder(this).build().also {
            localPlayer = it
            binding.localPlayer.player = it
        }
        val current = player.currentMediaItem?.localConfiguration?.uri?.toString()
        if (current != url) {
            player.setMediaItem(MediaItem.fromUri(url!!))
            player.prepare()
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
}
