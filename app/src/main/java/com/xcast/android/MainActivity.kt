package com.xcast.android

import android.content.Intent
import android.os.Bundle
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
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
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.MediaTrack
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.material.color.MaterialColors
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

    private val progressListener = RemoteMediaClient.ProgressListener { position, duration ->
        if (!userSeeking) updateSeekUi(position, duration)
        updatePlayPauseLabel()
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
            render(viewModel.uiState.value)
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
        detachRemote()
        castContext?.sessionManager?.removeSessionManagerListener(sessionListener, CastSession::class.java)
        super.onPause()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.cast, menu)
        CastButtonFactory.setUpMediaRouteButton(applicationContext, menu, R.id.media_route_menu_item)
        mediaRouteMenuItem = menu.findItem(R.id.media_route_menu_item)
        return true
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
        binding.castNowButton.isEnabled = state.video != null && currentSession() != null
        binding.castNowButton.text = when {
            state.video == null -> getString(R.string.cast_to_tv)
            currentSession() == null -> getString(R.string.connect_then_cast)
            else -> getString(R.string.cast_to_tv)
        }
        state.video?.let { video ->
            binding.previewTitle.text = video.title
            binding.previewSubtitle.text = video.subtitle
        }
        binding.previewCard.isVisible = state.video != null
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
        CastController.load(
            session,
            video,
            videoTrack,
            viewModel.selectedAudio(),
            viewModel.selectedCaption(),
            onSuccess = {
                attachRemote()
                binding.remoteCard.isVisible = true
            },
            onFailure = { error ->
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
            Toast.makeText(this, R.string.connect_cast_first, Toast.LENGTH_LONG).show()
            openCastDialog()
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
}
