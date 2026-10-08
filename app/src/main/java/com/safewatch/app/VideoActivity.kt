package com.safewatch.app

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.safewatch.app.browser.WatchActivity
import com.safewatch.app.data.Prefs
import com.safewatch.app.data.Video
import com.safewatch.app.data.VideoPage
import com.safewatch.app.data.YouTubeData
import com.safewatch.app.ui.Images
import com.safewatch.app.ui.Sounds
import com.safewatch.app.ui.Ui
import org.json.JSONObject

/**
 * One YouTube video's page in SafeWatch's layout: the picture with a play
 * button, the channel with a Follow button, the description, what to watch
 * next, and comments. Playing opens the app's player.
 */
class VideoActivity : AppCompatActivity() {

    private lateinit var video: Video
    private lateinit var details: LinearLayout
    private var channelId: String? = null
    private var channelName = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        video = try {
            Video.fromJson(JSONObject(intent.getStringExtra(EXTRA_VIDEO).orEmpty()))
        } catch (e: Exception) {
            finish()
            return
        }
        channelId = video.channelId
        channelName = video.channel
        val (page, column) = Ui.page(this, padded = false, ownWindow = true)
        val side = Ui.dp(this, 20)

        // The picture, 16:9, with a play button in the middle.
        column.addView(FrameLayout(this).apply {
            val width = resources.displayMetrics.widthPixels
            layoutParams = LinearLayout.LayoutParams(-1, width * 9 / 16)
            setBackgroundColor(Color.BLACK)
            addView(ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                Images.load(video.largeThumbnail, this, minWidth = 480)
            }, FrameLayout.LayoutParams(-1, -1))
            addView(View(context).apply { setBackgroundColor(Color.argb(70, 0, 0, 0)) }, FrameLayout.LayoutParams(-1, -1))
            addView(FrameLayout(context).apply {
                contentDescription = "Play"
                background = Ui.rounded(Ui.color(context, R.color.accent), Ui.dp(context, 34).toFloat())
                addView(Ui.icon(context, R.drawable.ic_play, R.color.on_accent, 30), FrameLayout.LayoutParams(Ui.dp(context, 30), Ui.dp(context, 30), Gravity.CENTER))
            }, FrameLayout.LayoutParams(Ui.dp(context, 68), Ui.dp(context, 68), Gravity.CENTER))
            addView(Ui.iconButton(context, R.drawable.ic_back, "Back", R.color.on_accent) { finish() }.apply {
                background = Ui.rounded(Color.argb(120, 0, 0, 0), Ui.dp(context, 23).toFloat())
            }, FrameLayout.LayoutParams(Ui.dp(context, 46), Ui.dp(context, 46)).apply {
                leftMargin = Ui.dp(context, 12)
                topMargin = Ui.dp(context, 8)
            })
            setOnClickListener { play() }
        })

        column.addView(TextView(this).apply {
            text = video.title
            textSize = 20f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(Ui.color(context, R.color.text))
            setPadding(side, Ui.dp(context, 16), side, 0)
        })
        if (video.meta.isNotEmpty()) column.addView(TextView(this).apply {
            text = video.meta
            textSize = 13f
            setTextColor(Ui.color(context, R.color.text_secondary))
            setPadding(side, Ui.dp(context, 4), side, 0)
        })
        column.addView(FrameLayout(this).apply {
            setPadding(side, Ui.dp(context, 16), side, 0)
            addView(Ui.actionButton(context, "Play", iconRes = R.drawable.ic_play) { play() })
        })

        details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(details)
        showChannel()
        setContentView(page)
        load()
    }

    private fun play() {
        Sounds.play(this, Sounds.PLAY)
        WatchActivity.open(this, WatchActivity.youtube(video.id), video.title)
    }

    private fun channelRow(): View {
        val id = channelId
        val following = id != null && id in Prefs.followedChannels(this)
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(context, 20), Ui.dp(context, 18), Ui.dp(context, 20), 0)
            addView(Ui.monogram(context, channelName.ifEmpty { "?" }))
            addView(TextView(context).apply {
                text = channelName
                textSize = 16f
                maxLines = 1
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(Ui.color(context, R.color.text))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            if (id != null) addView(Ui.pill(context, if (following) "Following" else "Follow", filled = !following) {
                Prefs.setFollowing(context, id, channelName, !following)
                showChannel()
            })
        }
    }

    private var channelView: View? = null

    private fun showChannel() {
        channelView?.let { details.removeView(it) }
        channelView = channelRow().also { details.addView(it, 0) }
    }

    private fun load() {
        Thread {
            val page = try { YouTubeData.page(video.id) } catch (e: Exception) { null }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (page == null) {
                    details.addView(Ui.caption(this, "The rest of this page could not be loaded. The video can still be played.").apply {
                        setPadding(Ui.dp(context, 20), Ui.dp(context, 20), Ui.dp(context, 20), 0)
                    })
                } else {
                    show(page)
                }
            }
        }.start()
    }

    private fun show(page: VideoPage) {
        val side = Ui.dp(this, 20)
        if (channelId == null && page.channelId != null) {
            channelId = page.channelId
            if (channelName.isEmpty()) channelName = page.channel
            showChannel()
        }
        if (page.description.isNotEmpty()) {
            val text = TextView(this).apply {
                this.text = page.description
                textSize = 14f
                maxLines = 4
                ellipsize = android.text.TextUtils.TruncateAt.END
                setLineSpacing(Ui.dp(context, 2).toFloat(), 1f)
                setTextColor(Ui.color(context, R.color.text))
                setPadding(Ui.dp(context, 14), Ui.dp(context, 12), Ui.dp(context, 14), Ui.dp(context, 12))
                // Tap to read the whole description.
                setOnClickListener { maxLines = if (maxLines == 4) Int.MAX_VALUE else 4 }
            }
            details.addView(FrameLayout(this).apply {
                setPadding(side, Ui.dp(context, 16), side, 0)
                addView(Ui.card(context).apply { addView(text) })
            })
        }
        if (page.related.isNotEmpty()) {
            details.addView(Ui.shelfTitle(this, "Up next"))
            page.related.take(12).forEach { next -> details.addView(Ui.videoRow(this, next) { open(this, next) }) }
        }
        val token = page.commentsToken ?: return
        val holder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        details.addView(Ui.shelfTitle(this, "Comments"))
        details.addView(holder)
        holder.addView(FrameLayout(this).apply {
            setPadding(side, 0, side, 0)
            addView(Ui.actionButton(context, "Show comments", filled = false) { loadComments(token, holder) })
        })
    }

    private fun loadComments(token: String, holder: LinearLayout) {
        val side = Ui.dp(this, 20)
        holder.removeAllViews()
        holder.addView(Ui.caption(this, "Loading comments…").apply { setPadding(side, 0, side, 0) })
        Thread {
            val comments = try { YouTubeData.comments(token) } catch (e: Exception) { null }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                holder.removeAllViews()
                if (comments.isNullOrEmpty()) {
                    holder.addView(Ui.caption(this, "No comments could be loaded.").apply { setPadding(side, 0, side, 0) })
                    return@runOnUiThread
                }
                for (c in comments.take(30)) holder.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(side, 0, side, Ui.dp(context, 16))
                    addView(TextView(context).apply {
                        text = listOf(c.author, if (c.likes.isEmpty() || c.likes == "0") "" else "${c.likes} likes").filter { it.isNotEmpty() }.joinToString("  ·  ")
                        textSize = 12f
                        setTextColor(Ui.color(context, R.color.text_secondary))
                    })
                    addView(TextView(context).apply {
                        text = c.text
                        textSize = 14f
                        setLineSpacing(Ui.dp(context, 2).toFloat(), 1f)
                        setTextColor(Ui.color(context, R.color.text))
                        setPadding(0, Ui.dp(context, 2), 0, 0)
                    })
                })
            }
        }.start()
    }

    companion object {
        private const val EXTRA_VIDEO = "video"

        fun open(ctx: Context, video: Video) =
            ctx.startActivity(Intent(ctx, VideoActivity::class.java).putExtra(EXTRA_VIDEO, video.toJson().toString()))
    }
}
