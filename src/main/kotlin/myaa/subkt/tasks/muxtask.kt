package myaa.subkt.tasks

import com.google.gson.*
import myaa.subkt.ass.ASSFile
import myaa.subkt.tasks.utils.*
import org.gradle.api.file.ConfigurableFileTree
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.*
import org.gradle.api.tasks.util.PatternFilterable
import org.gradle.kotlin.dsl.listProperty
import org.gradle.kotlin.dsl.property
import java.io.File
import java.io.Serializable
import java.lang.RuntimeException
import java.lang.reflect.Type
import java.nio.ByteBuffer
import java.util.zip.CRC32
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberProperties

/**
 * Marks a property with its corresponding mkvmerge flag.
 */
annotation class MuxFlag(val flag: String)

/**
 * Represents an item that can be optionally discarded.
 */
interface Filterable {
    /**
     * Whether to include the item.
     */
    val include: Property<Boolean>

    /**
     * The ID of the item.
     */
    val id: Long
}

/**
 * Filter files by file extension. Case-insensitive.
 */
fun PatternFilterable.includeExtensions(vararg extensions: String) {
    include { it.file.extension.toLowerCase() in extensions }
}

/**
 * Task to mux a set of files into a single Matroska container using mkvmerge.
 *
 * A predefined task instance can be accessed through [Subs.mux].
 *
 * @sample myaa.subkt.tasks.samples.muxSample
 */
open class Mux : PropertyTask() {
    /**
     * Represents an attachment present in a [MuxFile].
     */
    inner class Attachment(
            /**
             * Raw attachment info from mkvmerge.
             */
            @get:Internal val sourceAttachment: MkvAttachment
    ) : Filterable {
        /**
         * Stores information extracted from the attachment.
         */
        inner class AttachmentInfo {
            @get:Internal
            val fileName = sourceAttachment.file_name
            @get:Internal
            val contentType = sourceAttachment.content_type
            @get:Internal
            val size = sourceAttachment.size
            @get:Internal
            val type = sourceAttachment.type
        }

        /**
         * An [AttachmentInfo] instance providing information about the attachment.
         */
        @get:Internal
        val attachment = AttachmentInfo()

        /**
         * The ID of this attachment.
         */
        @get:Internal
        override val id = sourceAttachment.id

        /**
         * Whether to include this attachment in the output file. Defaults to true.
         */
        @get:Input
        override val include = defaultProperty(true)

        override fun toString() = "Attachment ${attachment.fileName} (${attachment.contentType})"
    }

    /**
     * The type of a track, available through [Track.TrackInfo.type].
     */
    enum class TrackType(val type: String?, val flag: String?) {
        AUDIO("audio", "audio"),
        VIDEO("video", "video"),
        SUBTITLES("subtitles", "subtitle"),
        BUTTONS("buttons", "button"),
        OTHER(null, null);

        companion object {
            private val lookup = values().associateBy { it.type }
            fun find(s: String) = lookup[s] ?: OTHER
        }
    }

    /**
     * The type of compression to use, for use with [Track.compression].
     */
    enum class CompressionType(val comp: String) {
        NONE("none"),
        ZLIB("zlib"),
        MPEG4P2("mpeg4p2");

        override fun toString() = comp
    }

    /**
     * Represents the dimensions of a track in pixels.
     */
    data class Dimensions(val width: Int, val height: Int) : Serializable {
        override fun toString() = "${width}x${height}"

        companion object {
            fun fromString(s: String) =
                    s.split("x").map(String::toInt)
                            .let { (x, y) -> Dimensions(x, y) }
        }
    }

    /**
     * Represents a duration for use with [Track.defaultDuration].
     */
    data class TrackDuration(val duration: Double, val durationUnit: DurationUnit) : Serializable {
        enum class DurationUnit(val unit: String) {
            SECONDS("s"),
            MILLISECONDS("ms"),
            MICROSECONDS("us"),
            NANOSECONDS("ns"),
            FPS("fps"),
            PROGRESSIVE("p"),
            INTERLACED("i")
        }

        override fun toString() = "$duration.$durationUnit.unit"
    }

    /**
     * Represents a single track read from the source file.
     */
    inner class Track(
            /**
             * Raw track info from mkvmerge.
             */
            @get:Internal val sourceTrack: MkvTrack
    ) : Filterable {
        /**
         * Stores information extracted from the source track.
         */
        inner class TrackInfo {
            val codec = sourceTrack.codec
            val codecId = sourceTrack.properties?.codec_id
            val audioChannels = sourceTrack.properties?.audio_channels
            val type = TrackType.find(sourceTrack.type)
            val typeString = sourceTrack.type
            val pixelDimensions = sourceTrack.properties
                    ?.pixel_dimensions?.let { Dimensions.fromString(it) }
            val lang = sourceTrack.properties?.language
            val name = sourceTrack.properties?.track_name
            val default = sourceTrack.properties?.default_track
            val forced = sourceTrack.properties?.forced_track
            val commentary = sourceTrack.properties?.commentary_track
            val hearingImpaired = sourceTrack.properties?.hearing_impaired_track
            val visualImpaired = sourceTrack.properties?.visual_impaired_track
            val displayDimensios = sourceTrack.properties
                    ?.display_dimensions?.let { Dimensions.fromString(it) }
            val encoding = sourceTrack.properties?.encoding
        }

        /**
         * A [TrackInfo] instance providing information about the source track.
         */
        @get:Internal
        val track = TrackInfo()

        /**
         * The ID of this track.
         */
        @get:Internal
        override val id = sourceTrack.id

        /**
         * Whether to include this track in the output file. Defaults to true.
         */
        @get:Input
        override val include = defaultProperty(true)

        /**
         * Sets the language of this track.
         *
         * Corresponds to the `--language` mkvmerge flag.
         */
        @get:Input
        @get:Optional
        @MuxFlag("language")
        val lang = project.objects.property<String>()

        /**
         * Sets the name of this track.
         *
         * Corresponds to the `--track-name` mkvmerge flag.
         */
        @get:Input
        @get:Optional
        @MuxFlag("track-name")
        val name = project.objects.property<String>()

        /**
         * If true, set this track to be a default track.
         *
         * Corresponds to the `--default-track` mkvmerge flag.
         */
        @get:Input
        @get:Optional
        @MuxFlag("default-track")
        val default = project.objects.property<Boolean>()

        /**
         * If true, set this track to be a forced track.
         *
         * Corresponds to the `--forced-track` mkvmerge flag.
         */
        @get:Input
        @get:Optional
        @MuxFlag("forced-track")
        val forced = project.objects.property<Boolean>()

        /**
         * If true, set this track to be a commentary track.
         *
         * Corresponds to the `--commentary` mkvmerge flag.
         */
        @get:Input
        @get:Optional
        @MuxFlag("commentary-flag")
        val commentary = project.objects.property<Boolean>()

        /**
         * If true, set this track to be a hearing impaired track.
         *
         * Corresponds to the `--hearing-impaired` mkvmerge flag.
         */
        @get:Input
        @get:Optional
        @MuxFlag("hearing-impaired-flag")
        val hearingImpaired = project.objects.property<Boolean>()

        /**
         * If true, set this track to be a visual impaired track.
         *
         * Corresponds to the `--visual-impaired` mkvmerge flag.
         */
        @get:Input
        @get:Optional
        @MuxFlag("visual-impaired-flag")
        val visualImpaired = project.objects.property<Boolean>()

        /**
         * Sets the display dimensions of this track, specified as a [Dimensions] object, e.g.:
         *
         * ```
         * displayDimensions(Dimensions(1920, 1080))
         * ```
         *
         * Corresponds to the `--display-dimensions` mkvmerge flag.
         */
        @get:Input
        @get:Optional
        @MuxFlag("display-dimensions")
        val displayDimensions = project.objects.property<Dimensions>()

        /**
         * Sets the aspect ratio of this track, specified as a floating point value.
         *
         * Corresponds to the `--aspect-ratio` mkvmerge flag.
         */
        @get:Input
        @get:Optional
        @MuxFlag("aspect-ratio")
        val aspectRatio = project.objects.property<Double>()

        /**
         * Sets the compression type for this track. Must be one of [CompressionType.ZLIB],
         * [CompressionType.MPEG4P2] or [CompressionType.NONE].
         *
         * Corresponds to the `--compression` mkvmerge flag.
         */
        @get:Input
        @get:Optional
        @MuxFlag("compression")
        val compression = project.objects.property<CompressionType>()

        /**
         * Sets the delay of the track in milliseconds.
         *
         * Set using the `--sync` mkvmerge flag.
         */
        @get:Input
        @get:Optional
        val delay = project.objects.property<Long>()

        /**
         * Value to multiply the timestamps by. E.g. a value of
         * 2 makes the track twice as long.
         *
         * Set using the `--sync` mkvmerge flag.
         */
        @get:Input
        @get:Optional
        val stretch = project.objects.property<Double>()

        @MuxFlag("sync")
        @get:Internal
        val sync = getSync(delay, stretch)

        /**
         * Forces the default duration or FPS for the track. Value must be a
         * [TrackDuration] instance, e.g.:
         *
         * ```
         * defaultDuration(Duration(24, TrackDuration.DurationUnit.FPS))
         * ```
         *
         * Corresponds to the `--default-duration` mkvmerge flag.
         */
        @get:Input
        @get:Optional
        @MuxFlag("default-duration")
        val defaultDuration = project.objects.property<TrackDuration>()

        /**
         * The order of this track in the muxed file. Tracks will be sorted
         * by the values specified for this property.
         *
         * The track order values specified do not need to be consecutive.
         * Tracks with the same [trackOrder] will keep their original relative ordering.
         */
        @get:Input
        @get:Optional
        val trackOrder = project.objects.property<Int>()

        override fun toString() = ("Track ${track.typeString} " +
                "(${(name.orNull ?: track.name)?.let { "$it, " } ?: ""}" +
                "${track.codec}, " +
                "${lang.orNull ?: track.lang ?: "und"})")
    }

    /**
     * Represents a file to mux, added using [from].
     */
    inner class MuxFile(
            /**
             * The source file.
             */
            @get:Internal
            val file: File
    ) {
        /**
         * Raw file info from mkvmerge.
         */
        @get:Internal
        val info = getMkvInfo(file, mkvmerge.get())

        /**
         * The number of chapter entries present in this file.
         */
        @get:Internal
        val chapters = info.chapters?.getOrNull(0)?.num_entries

        /**
         * The tracks present in this file.
         */
        @get:Nested
        val tracks = info.tracks.orEmpty().map(::Track)

        /**
         * The attachments present in this file.
         */
        @get:Nested
        val attachments = info.attachments.orEmpty().map(::Attachment)

        /**
         * Whether to keep the chapters present in this file, if any.
         * Defaults to true.
         */
        @get:Input
        val includeChapters = defaultProperty(true)

        /**
         * Any additional file-specific mkvmerge options you wish to include.
         *
         * If you wish to include track-specific options, you must manually prepend
         * the option value with the track ID. This can be done by accessing the
         * [Track.id] value of a track.
         *
         * @sample myaa.subkt.tasks.samples.muxFileFileOptionsSample
         */
        @get:Input
        val fileOptions = project.objects.listProperty<String>()

        /**
         * Configures the tracks in this file.
         *
         * @sample myaa.subkt.tasks.samples.muxFileTracksSample
         * @param trackIds The IDs of the tracks to run [action] against, starting at 0.
         * If [trackType] is specified, 0 will refer to the first track of that type.
         * @param trackType The type of the tracks to run [action] against.
         * @param action A closure operating on a [Track] instance.
         */
        fun tracks(vararg trackIds: Int, trackType: TrackType? = null,
                   action: Track.() -> Unit = {}): List<Track> {
            val tracksOfType = trackType
                    ?.let { type -> tracks.filter { it.track.type == type } } ?: tracks
            val tracksOfId = tracksOfType.takeIf { trackIds.isEmpty() }
                    ?: tracksOfType.slice(trackIds.asIterable())
            tracksOfId.forEach(action)
            return tracksOfId
        }

        /**
         * Configures the video tracks in this file.
         *
         * @sample myaa.subkt.tasks.samples.muxFileTracksSample
         * @param trackIds The video tracks to run [action] against.
         * 0 refers to the first video track, 1 to the second video track, and so on.
         * @param action A closure operating on a [Track] instance.
         */
        fun video(vararg trackIds: Int, action: Track.() -> Unit = {}) =
                tracks(*trackIds, trackType = TrackType.VIDEO, action = action)

        /**
         * Configures the audio tracks in this file.
         *
         * @sample myaa.subkt.tasks.samples.muxFileTracksSample
         * @param trackIds The audio tracks to run [action] against.
         * 0 refers to the first audio track, 1 to the second audio track, and so on.
         * @param action A closure operating on a [Track] instance.
         */
        fun audio(vararg trackIds: Int, action: Track.() -> Unit = {}) =
                tracks(*trackIds, trackType = TrackType.AUDIO, action = action)

        /**
         * Configures the subtitle tracks in this file.
         *
         * @sample myaa.subkt.tasks.samples.muxFileTracksSample
         * @param trackIds The subtitle tracks to run [action] against.
         * 0 refers to the first subtitle track, 1 to the second subtitle track, and so on.
         * @param action A closure operating on a [Track] instance.
         */
        fun subtitles(vararg trackIds: Int, action: Track.() -> Unit = {}) =
                tracks(*trackIds, trackType = TrackType.SUBTITLES, action = action)

        /**
         * Configures the attachments in this file.
         *
         * @sample myaa.subkt.tasks.samples.muxFileAttachmentsSample
         * @param action A closure operating on an [Attachment] instance.
         */
        fun attachments(action: Attachment.() -> Unit) = attachments.forEach(action)

        override fun toString() = sequence {
            val fname = file.name
            tracks.filter { it.include.get() }.forEach { yield("$it [$fname]") }
            attachments.filter { it.include.get() }.forEach { yield("$it [$fname]") }

            chapters?.takeIf { includeChapters.get() }?.let { yield("$it chapters [$fname]") }
        }.joinToString("\n")
    }

    /**
     * Represents a chapter file to mux, added using [chaptersProperty].
     */
    inner class Chapter(
            /**
             * The source file.
             */
            @get:Internal
            val file: File
    ) {
        /**
         * Sets the language of the chapters.
         *
         * Corresponds to the `--chapter-language` mkvmerge flag.
         */
        @get:Input
        @get:Optional
        @MuxFlag("chapter-language")
        val lang = project.objects.property<String>()

        /**
         * Sets the character encoding of the chapter file.
         *
         * Corresponds to the `--chapter-charset` mkvmerge flag.
         */
        @get:Input
        @get:Optional
        @MuxFlag("chapter-charset")
        val charset = project.objects.property<String>()

        /**
         * Sets the delay of the chapters in milliseconds.
         *
         * Set using the `--chapter-sync` mkvmerge flag.
         */
        @get:Input
        @get:Optional
        val delay = project.objects.property<Long>()

        /**
         * Value to multiply the timestamps by. E.g. a value of
         * 2 makes the track twice as long.
         *
         * Set using the `--chapter-sync` mkvmerge flag.
         */
        @get:Input
        @get:Optional
        val stretch = project.objects.property<Double>()

        @MuxFlag("chapter-sync")
        @get:Internal
        val sync = getSync(delay, stretch)
    }

    private val _attachments = project.objects.listProperty<FileCollection>()

    /**
     * The files to attach, added via [attach].
     */
    @get:InputFiles
    val attachmentsProperty: Provider<List<FileCollection>> = _attachments

    private val _files = project.objects.listProperty<MuxFile>().apply { finalizeValueOnRead() }

    /**
     * The files to mux, added via [from].
     */
    @get:Nested
    val filesProperty: Provider<List<MuxFile>> = _files

    private val _chapters = project.objects.property<Chapter>()

    /**
     * The chapters of the file, added via [chapters].
     */
    @get:Nested
    @get:Optional
    val chaptersProperty: Provider<Chapter> = _chapters

    /**
     * Path to the mkvmerge command. Defaults to mkvmerge, and assumes the
     * command is present in your PATH.
     */
    @get:Input
    val mkvmerge = defaultProperty("mkvmerge")

    /**
     * Whether to output a WebM compliant file. Defaults to false.
     */
    @get:Input
    val webm = defaultProperty(false)

    /**
     * If true, a fixed value will be used as the RNG seed for generating UIDs,
     * and mkvmerge will not write the current date and time to the output file.
     * This means that for the same input files, flags and mkvmerge version,
     * the output file will always be byte identical.
     * Thus, even if you have to rerun tasks for whatever reason, you know that
     * the same CRC32 will be generated unless the input files have changed.
     */
    @get:Input
    val deterministic = defaultProperty(true)

    /**
     * The seed to use for `--deterministic` -- see [deterministic].
     * If not set, a hash based on the output filename will be used.
     *
     * To avoid potential issues with e.g. segment UID clashes for ordered chapters,
     * you should make sure to use different seeds for different output files,
     * or alternatively specify segment UIDs manually.
     */
    @get:Input
    @get:Optional
    val deterministicSeed = project.objects.property<Long>()

    /**
     * The title of the file. Will generally show up in e.g. the window title of the video player.
     */
    @get:Input
    @get:Optional
    @MuxFlag("title")
    val title = project.objects.property<String>()

    /**
     * The default language used for tracks which don't have a language specified.
     * If not set, mkvmerge will default to `und`.
     */
    @get:Input
    @get:Optional
    @MuxFlag("default-language")
    val defaultLanguage = project.objects.property<String>()

    /**
     * Overrides the MIME types autodetected by mkvmerge for files attached via [attach],
     * on a file extension basis. E.g. to force all files ending with `.txt` to have
     * MIME type `text/html`, you can run:
     *
     * ```
     * mimeTypes["txt"] = "text/html"
     * ```
     *
     * The file extension should be specified in lower case, and will be matched against
     * attached files *case-insensitively* (i.e. `ttf` will match both files ending
     * in `.ttf` and `.TTF`).
     *
     * By default, `ttf` is mapped to `application/x-truetype-font`, and `otf` to
     * `application/vnd.ms-opentype`.
     */
    @get:Input
    val mimeTypes = mutableMapOf(
            "ttf" to "application/x-truetype-font",
            "otf" to "application/vnd.ms-opentype"
    )

    /**
     * Forces the CRC32 hash of the file to be equal to the set value.
     * Must be a 8 character long hex string, containing only digits
     * and letters in the range A-F.
     */
    @get:Input
    @get:Optional
    val forceCRC = project.objects.property<String>()

    /**
     * Any additional global mkvmerge options you wish to include.
     *
     * @sample myaa.subkt.tasks.samples.muxGlobalOptionsSample
     */
    @get:Input
    val globalOptions = project.objects.listProperty<String>()

    /**
     * If true, will verify the CRC of input files that have
     * a CRC specified in square brackets, e.g. `premux [DEADBEEF].mkv`.
     * Defaults to true.
     */
    @get:Internal
    val verifyCRC = defaultProperty(true)

    /**
     * If true, will not attach any font files that are not used in
     * any of the attached subtitle tracks.
     * Defaults to false.
     */
    @get:Internal
    val skipUnusedFonts = defaultProperty(false)

    /**
     * If true, will warn about font issues in included subtitle tracks.
     * You can configure the error reporting using [onFaux], [onStyleMismatch],
     * [onMissingGlyphs] and [onMissingFonts].
     * Defaults to true.
     */
    @get:Internal
    val verifyFonts = defaultProperty(true)

    /**
     * If [verifyFonts] is true, controls what to do if an instance of
     * faux bold or faux italic is encountered.
     * Defaults to [ErrorMode.WARN].
     */
    @get:Internal
    val onFaux = defaultProperty(ErrorMode.WARN)

    /**
     * If [verifyFonts] is true, controls what to do if the matched font
     * differs from the requested style (e.g. non-italic was requested, but only italic found).
     * Defaults to [ErrorMode.WARN].
     */
    @get:Internal
    val onStyleMismatch = defaultProperty(ErrorMode.WARN)

    /**
     * If [verifyFonts] is true, controls what to do if missing glyphs are encountered.
     * Defaults to [ErrorMode.FAIL].
     */
    @get:Internal
    val onMissingGlyphs = defaultProperty(ErrorMode.FAIL)

    /**
     * If [verifyFonts] is true, controls what to do if a missing font is encountered.
     * Defaults to [ErrorMode.FAIL].
     */
    @get:Internal
    val onMissingFonts = defaultProperty(ErrorMode.FAIL)

    /**
     * The CRC32 hash of the output file. Only available after the task has finished.
     */
    @get:Internal
    var crc by TaskProperty { "XXXXXXXX" }
        private set

    /**
     * Information about the output file as returned by `mkvinfo -J`.
     * See the [mkvmerge identification output schema](https://mkvtoolnix.download/doc/mkvmerge-identification-output-schema-v12.json).
     * Additionally, the map contains the keys `video_tracks`, `audio_tracks` and `subtitles_tracks`
     * which are lists of tracks of the respective types.
     *
     * Example:
     *
     * ```
     * res=$mux.info["video_tracks"][0]["properties"]["display_dimensions"].split("x")[1]
     * acodec=$mux.info["audio_tracks"][0]["properties"]["codec_id"].substring(2)
     * filename=$title - $episode (${source} ${res}p ${acodec}) [${mux.crc}].mkv
     * ```
     */
    @get:Internal
    var info by TaskProperty { mutableMapOf<String, Any>() }
        private set

    /**
     * The location to save the MKV file.
     * Defaults to an automatically generated file in the build directory.
     */
    @get:Internal
    val out = outputFile("mkv")

    // Gradle will finalize the file collection before the task is run
    // if out is annotated with OutputFile, meaning that getting the file
    // after calculating the crc will return the same filename.
    // instead use a Provider that can't be finalized to force Gradle
    // to reevaluate the filename
    /**
     * Read-only alias of [out].
     */
    @get:OutputFile
    val outFile: Provider<File> = project.provider { out.singleFile }

    private val _inputFiles = project.objects.fileCollection()

    /**
     * All files added via [from], [attach] or [chapters].
     */
    @get:InputFiles
    val inputFiles: FileCollection = _inputFiles

    /**
     * Adds files to mux.
     *
     * @sample myaa.subkt.tasks.samples.muxFromSample
     * @param files The files to mux.
     * @param action A closure operating on a [MuxFile] instance
     * for customizing the output.
     */
    fun from(vararg files: Any, action: MuxFile.() -> Unit = {}): Provider<List<MuxFile>> {
        _inputFiles.from(files)
        return project.providers.provider {
            project.files(files).map { file ->
                MuxFile(file).apply(action)
            }
        }.also { _files.addAll(it) }
    }

    /**
     * Adds a chapter file.
     *
     * @sample myaa.subkt.tasks.samples.muxChaptersSample
     * @param file The file containing the chapters.
     * @param action A closure operating on a [Chapter] instance.
     */
    fun chapters(file: Any, action: Chapter.() -> Unit = {}): Provider<Chapter> {
        _inputFiles.from(file)
        return project.provider {
            Chapter(project.files(file).singleFile).apply(action)
        }.also { _chapters.set(it) }
    }

    /**
     * Attach one or more files to the output file.
     *
     * @sample myaa.subkt.tasks.samples.muxAttachSample
     * @param dirs A directory, collection of directories, or a single file
     * @param action A closure operating on a [ConfigurableAttachment] instance,
     * allowing you to filter what files to include.
     */
    fun attach(vararg dirs: Any, action: ConfigurableFileTree.() -> Unit = {}):
            Provider<List<ConfigurableFileTree>> {
        _inputFiles.from(dirs)
        return project.providers.provider {
            project.files(dirs).map { dir ->
                project.fileTree(dir).apply(action)
            }
        }.also { _attachments.addAll(it) }
    }

    init {
        outputs.upToDateWhen {
            outFile.get().exists()
        }
    }

    private fun getSync(delay: Property<Long>, stretch: Property<Double>): Provider<String> =
            stretch.map { "${delay.getOrElse(0)},$it/1" }
                    .orElse(delay.map { it.toString() })

    private fun calculateCRC(outFile: File): Long {
        val crc32 = CRC32()
        return outFile.inputStream().use {
            generateSequence { it.readNBytes(1024*1024) }
                    .takeWhile { it.isNotEmpty() }
                    .forEach { crc32.update(it) }
            crc32.value
        }
    }

    private fun crcToString(crc: Long) = "%08X".format(crc)

    /* ported from https://github.com/dreamer2908/Python-CRC32-Forcer/blob/d574683049ec64d163fd51d6478e624a4c3086bc/python_crc32_forcer.py */
    private fun calculateForcedCRC(oldcrc: Long, targetcrc: Long): ByteArray {
        val CRCPOLY = 0xEDB88320L
        val CRCINV = 0x5B358FD3L
        val FINALXOR = 0xFFFFFFFFL

        val original = oldcrc xor FINALXOR

        val (_, newContents) = (0 until 32).fold(Pair(targetcrc xor FINALXOR, 0x0L)) {
            (target, newContents), _ ->
            val newContShift = if (newContents and 1L != 0L) {
                (newContents shr 1) xor CRCPOLY
            } else {
                newContents shr 1
            }

            val updatedContents = newContShift.takeIf { target and 1L == 0L }
                    ?: newContShift xor CRCINV

            Pair(target shr 1, updatedContents)
        }

        val finalContents = newContents xor original

        return ByteBuffer.allocate(8).putLong(finalContents).array().drop(4)
                .reversed().toByteArray()
    }

    private inline fun <reified T : Any> getSetFields(obj: T) =
            T::class.memberProperties.mapNotNull { prop ->
                prop.findAnnotation<MuxFlag>()?.let { ann ->
                    val value = prop.get(obj) as Provider<*>
                    value.orNull?.let {
                        ann.flag to it
                    }
                }
            }

    private fun filterTracks(filterable: List<Filterable>,
                             includeFlag: String,
                             disableFlag: String): List<String> {
        val included = filterable.filter { it.include.get() }

        return if (included.size != filterable.size) {
            if (included.isEmpty()) {
                listOf(disableFlag)
            } else {
                listOf(includeFlag, included.joinToString(",") { it.id.toString() })
            }
        } else {
            listOf()
        }
    }

    private fun buildCommand(outFile: File) = sequence {
        yield(mkvmerge.get())

        if (deterministic.get()) {
            val seed = deterministicSeed.getOrElse(outFile.name.hashCode().toLong())
            yield("--deterministic")
            yield("$seed")
        }

        if (webm.get()) {
            yield("-w")
        }

        // global flags
        getSetFields(this@Mux).forEach { (flag, value) ->
            yield("--$flag")
            yield("$value")
        }

        _chapters.orNull?.let {
            logger.lifecycle("Attaching chapters:")
            logger.lifecycle(it.file.readText())
            getSetFields(_chapters.get()).forEach { (flag, value) ->
                yield("--$flag")
                yield("$value")
            }
            yield("--chapters")
            yield(it.file.absolutePath)
        }

        yieldAll(globalOptions.get())

        // output file
        yield("--output")
        yield(outFile.absolutePath)

        // per-track flags
        val files = _files.get()
        val attachments = _attachments.get().flatMap { project.files(it) }.toSet()

        @OptIn(ExperimentalStdlibApi::class)
        val unusedFonts = files.mapNotNull { file ->
            logger.lifecycle(file.toString())

            val unused = if (verifyFonts.get() && file.info.container?.properties?.container_type == 28L) {
                logger.lifecycle("Validating fonts for ${file.file.name}...")
                val report = verifyFonts(ASSFile(file.file), attachments)
                report.printReport(onMissingFonts.get(), onFaux.get(),
                        onStyleMismatch.get(), onMissingGlyphs.get())
                report.unusedFonts()
            } else { null }

            // options from included tracks
            file.tracks.filter { it.include.get() }.forEach { track ->
                getSetFields(track).forEach { (flag, value) ->
                    yield("--$flag")
                    yield("${track.id}:${value}")
                }
            }

            // include/disable tracks
            listOf(
                    TrackType.AUDIO,
                    TrackType.VIDEO,
                    TrackType.SUBTITLES,
                    TrackType.BUTTONS
            ).forEach { trackType ->
                yieldAll(filterTracks(
                        file.tracks.filter { it.track.type == trackType },
                        "--${trackType.flag}-tracks",
                        "--no-${trackType.type}"
                ))
            }

            // include/disable attachments
            yieldAll(filterTracks(
                    file.attachments,
                    "--attachments",
                    "--no-attachments"
            ))

            if (!file.includeChapters.get()) {
                yield("--no-chapters")
            }

            // custom per-file options
            yieldAll(file.fileOptions.get())

            // output file
            yield(file.file.absolutePath)

            unused
        }.reduceOrNull { acc, files -> files.intersect(acc) } ?: setOf()

        val trackOrder = files.withIndex().flatMap { (i, file) ->
            file.tracks.mapNotNull { track ->
                track.trackOrder.orNull?.let { "$i:${track.id}" to it }
            }
        }.sortedBy { (_, priority) -> priority }.map { (track, _) -> track }

        if (trackOrder.isNotEmpty()) {
            yield("--track-order")
            yield(trackOrder.joinToString(","))
        }

        val toAttach = if (skipUnusedFonts.get()) {
            println("Not attaching unused fonts: " +
                    unusedFonts.joinToString(", ") { it.name })
            attachments - unusedFonts
        } else {
            attachments
        }

        // attachments
        toAttach.forEach {
            val mime = mimeTypes[it.extension.toLowerCase()]
            logger.lifecycle("Attaching ${it.name} (content-type: ${mime ?: "autodetected"})")
            mime?.let {
                yield("--attachment-mime-type")
                yield(it)
            }

            yield("--attach-file")
            yield(it.absolutePath)
        }
    }

    private val crcPattern = Regex("""\[([0-9a-fA-F]{8})]""")

    override fun run() {
        if (verifyCRC.get()) {
            _inputFiles.forEach { f ->
                crcPattern.find(f.name)?.destructured?.let { (expectedCrc) ->
                    val actualCrc = calculateCRC(f)
                    if (actualCrc != expectedCrc.toLong(16)) {
                        error("Unexpected CRC for ${f.name}; expected $expectedCrc," +
                                " got ${crcToString(actualCrc)}")
                    }
                }
            }
        }

        // reset crc for more deterministic behaviour
        crc = "XXXXXXXX"

        val outFile = outFile.get()
        val tempLocation = outFile.resolveSibling(outFile.name + ".tmp")
        val command = buildCommand(tempLocation).toList()

        logger.lifecycle(command.toString())
        val proc = ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.PIPE)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start()

        val output = proc.inputStream.bufferedReader().readText();

        val result = proc.waitFor()
        if (result != 0) {
            throw RuntimeException("mkvmerge failed:\n$output")
        }

        val oldcrc = calculateCRC(tempLocation)

        crc = forceCRC.orNull?.let {
            if (it.length != 8) {
                throw IllegalArgumentException("not a valid CRC: $it")
            }

            val contentToAdd = calculateForcedCRC(oldcrc, it.toLong(16))
            tempLocation.appendBytes(contentToAdd)
            crcToString(calculateCRC(tempLocation))
        } ?: crcToString(oldcrc)

        // ugly hack to generate video_tracks etc more easily (using an MkvInfo instance)
        // while still converting to a MutableMap to make deserialization of
        // the cache files simpler (no need for PropertyTask to know about MkvInfo)
        val outInfo = getMkvInfo(tempLocation, mkvmerge.get())
        val outJson = GsonBuilder()
                .registerTypeAdapter(MkvInfo::class.java, MkvInfoSerializer)
                .create()
                .toJson(outInfo)
        info = Gson().fromJson(outJson, MutableMap::class.java) as MutableMap<String, Any>

        val renamed = this.outFile.get()
        logger.quiet("Output: ${renamed.name}")
        renamed.delete()
        tempLocation.renameTo(renamed)
    }
}


/**
 * Convenience property that upon use automatically instantiates and returns a
 * [TaskGroup] of type [Mux] with the name `mux`.
 */
val Subs.mux
    get() = task<Mux>("mux")
