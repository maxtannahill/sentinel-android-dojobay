package com.samourai.sentinel.ui.fragments

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.samourai.sentinel.data.db.SentinelRoomDb
import com.samourai.sentinel.data.repository.Bip329Importer
import com.samourai.sentinel.data.repository.ImportResult
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.StringReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.zip.Deflater

/**
 * QR label-import surface, JVM side: the door-check
 * (isValidLabelsJsonl), the decoded-blob hand-off — the exact
 * Reader shape the settings QR path feeds the importer — and BBQr
 * reassembly. The animated-UR encode/decode side stays on-device with
 * the #36 sitting; hummingbird is exercised there, not here. Same
 * fixture-first pattern as Bip329ImporterTest / Bip329ParserTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportLabelsScanBottomSheetTest {

    private val created = mutableListOf<SentinelRoomDb>()
    private lateinit var importer: Bip329Importer

    @Before
    fun setUp() {
        importer = freshImporter()
    }

    @After
    fun tearDown() {
        created.forEach { it.close() }
    }

    private fun freshImporter(): Bip329Importer {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            SentinelRoomDb::class.java
        ).allowMainThreadQueries().build()
        created.add(db)
        return Bip329Importer(db)
    }

    private fun fixture(): List<String> =
        javaClass.getResourceAsStream("/bip329/sample.jsonl")!!
            .bufferedReader().readLines().filter { it.isNotBlank() }

    private fun import(text: String): ImportResult = runBlocking {
        importer.import(BufferedReader(StringReader(text)), "mainnet", 1000L)
    }

    @Test
    fun doorCheckAcceptsLabelJsonl() {
        assertTrue(isValidLabelsJsonl(fixture().joinToString("\n")))
    }

    @Test
    fun doorCheckRejectsBlankAndGarbage() {
        assertFalse(isValidLabelsJsonl(""))
        assertFalse(isValidLabelsJsonl("   \n   \n"))
        assertFalse(isValidLabelsJsonl("not json at all"))
    }

    @Test
    fun doorCheckSkipsLeadingBlankLines() {
        val text = "\n\n" + fixture().joinToString("\n")
        assertTrue(isValidLabelsJsonl(text))
    }

    @Test
    fun decodedBlobImportsMatchFileShapeImports() {
        val blob = "\n" + fixture().joinToString("\n") + "\n"
        val baseline = freshImporter()
        val fromQr = import(blob)
        val fromFile = runBlocking {
            baseline.import(
                BufferedReader(StringReader(fixture().joinToString("\n"))),
                "mainnet", 1000L
            )
        }
        assertEquals(fromFile.imported, fromQr.imported)
        assertEquals(fromFile.updated, fromQr.updated)
        assertEquals(fromFile.skipped, fromQr.skipped)
    }

    @Test
    fun classifyRoutesUrAndBbqrFramesAwayFromDoorCheck() {
        assertEquals(LabelQrPayload.UrFrame, classifyLabelQrPayload("UR:BYTES/1-2/ABCDEF"))
        // Sparrow label export: zlib + base32, file type U, 1 part, index 0.
        assertEquals(LabelQrPayload.BbqrFrame, classifyLabelQrPayload("B\$ZU0100"))
        assertEquals(LabelQrPayload.Invalid, classifyLabelQrPayload("not json"))
    }

    @Test
    fun classifyAcceptsSpecBbqrHeadersAndRejectsTheOldBqrPrefix() {
        assertTrue(isBbqrFrame("B\$ZU0100"))
        assertTrue(isBbqrFrame("B\$HP0300"))
        assertTrue(isBbqrFrame("B\$HP0302ABCD"))
        assertTrue(isBbqrFrame("B\$2U0A00"))
        // File type is one base-36 character; digits are in that alphabet.
        assertTrue(isBbqrFrame("B\$H10000"))
        assertEquals(LabelQrPayload.BbqrFrame, classifyLabelQrPayload("B\$ZU0100HELLO"))

        assertFalse(isBbqrFrame("BQR:1/1/T/QUJD"))
        assertFalse(isBbqrFrame("BQR"))
        assertFalse(isBbqrFrame("B\$"))
        assertFalse(isBbqrFrame("B\$ZU010"))
        assertFalse(isBbqrFrame("b\$ZU0100"))
        assertFalse(isBbqrFrame("B\$zU0100"))
        assertFalse(isBbqrFrame("B\$XU0100"))
        assertEquals(LabelQrPayload.Invalid, classifyLabelQrPayload("BQR:1/1/T/QUJD"))
        assertEquals(LabelQrPayload.Invalid, classifyLabelQrPayload("b\$ZU0100"))
    }

    @Test
    fun classifyRoutesValidJsonlToLabels() {
        val text = fixture().joinToString("\n")
        assertEquals(LabelQrPayload.Labels(text), classifyLabelQrPayload(text))
    }

    @Test
    fun classifyAcceptsLowercaseUrScheme() {
        assertEquals(LabelQrPayload.UrFrame, classifyLabelQrPayload("ur:BYTES/1-2/ABCDEF"))
    }

    @Test
    fun doorCheckRoutesFramesToResumeAndJsonlToImport() {
        val text = fixture().joinToString("\n")
        assertEquals(LabelScanAction.Import(text), scan(text))
        // Empty Z body is not a zlib stream. It is a BBQr frame, so it
        // errors instead of importing, and it is not the old detect-only toast.
        assertEquals(
            LabelScanAction.Notify(BBQR_DECODE_FAILED_MESSAGE),
            scan("B\$ZU0100")
        )
        assertEquals(LabelScanAction.IgnoreAndResume, scan("BQR:1/1/T/QUJD"))
        assertEquals(LabelScanAction.IgnoreAndResume, scan("ur:BYTES/1-2/ABCDEF"))
        assertEquals(LabelScanAction.IgnoreAndResume, scan("not json"))
    }

    @Test
    fun parseBbqrHeaderReadsEncodingTypeCountAndIndex() {
        val onePart = parseBbqrHeader("B\$ZU0100")
        assertEquals('Z', onePart!!.encoding)
        assertEquals('U', onePart.fileType)
        assertEquals(1, onePart.partCount)
        assertEquals(0, onePart.partIndex)
        assertEquals("", onePart.body)
        assertEquals("B\$ZU01", onePart.signature)

        // Spec example: PSBT, hex, 3 parts, this is the last.
        val psbt = parseBbqrHeader("B\$HP0302ABCD")
        assertEquals('H', psbt!!.encoding)
        assertEquals('P', psbt.fileType)
        assertEquals(3, psbt.partCount)
        assertEquals(2, psbt.partIndex)
        assertEquals("ABCD", psbt.body)

        // 0B = 11 parts, 0A = index 10. Index has to sit inside the count.
        val base36 = parseBbqrHeader("B\$2J0B0A")
        assertEquals('2', base36!!.encoding)
        assertEquals('J', base36.fileType)
        assertEquals(11, base36.partCount)
        assertEquals(10, base36.partIndex)

        val maxParts = parseBbqrHeader("B\$HUZZ00")
        assertEquals(1295, maxParts!!.partCount)
        assertEquals(0, maxParts.partIndex)
    }

    @Test
    fun parseBbqrHeaderRejectsMalformedCountsAndTheOldBqrPrefix() {
        assertNull(parseBbqrHeader("BQR:1/1/T/QUJD"))
        assertNull(parseBbqrHeader("B\$ZU010"))
        assertNull(parseBbqrHeader("b\$ZU0100"))
        assertNull(parseBbqrHeader("B\$XU0100"))
        assertNull(parseBbqrHeader("B\$ZU0000"))
        assertNull(parseBbqrHeader("B\$ZU0102"))
        assertFalse(isBbqrFrame("BQR:1/1/T/QUJD"))
        assertEquals(LabelQrPayload.Invalid, classifyLabelQrPayload("BQR:1/1/T/QUJD"))
        assertEquals(LabelScanAction.IgnoreAndResume, scan("BQR:1/1/T/QUJD"))
    }

    @Test
    fun multiPartReassemblesOutOfOrderAndIgnoresRepeatedIndexes() {
        val raw = byteArrayOf(0x00, 0x01, 0x02, 0x10, 0x7f, 0xff.toByte())
        val frames = listOf(
            "B\$HU03000001",
            "B\$HU03010210",
            "B\$HU03027FFF",
        )
        val session = BbqrAccumulator()
        assertTrue(session.receive(frames[2]) is BbqrReceive.Pending)
        assertTrue(session.receive(frames[0]) is BbqrReceive.Pending)
        assertTrue(session.receive(frames[0]) is BbqrReceive.Pending)
        // An index past the header count is skipped. It does not toast
        // and it does not wipe the parts already stored.
        assertEquals(
            BbqrReceive.Pending(
                BbqrProgress(2, 3),
                storedNewPart = false,
                skip = BbqrSkip.Index,
                partIndex = 5,
            ),
            session.receive("B\$HU0305FFFF")
        )
        assertEquals(listOf(1), session.missingIndexes())
        assertEquals(
            LabelScanAction.Skipped(BbqrSkip.Index, partIndex = 5),
            scan("B\$HU0305FFFF", session)
        )
        val done = session.receive(frames[1])
        assertTrue(done is BbqrReceive.Complete)
        assertArrayEquals(raw, (done as BbqrReceive.Complete).bytes)
        assertEquals('U', done.fileType)
        assertTrue(session.receive(frames[1]) is BbqrReceive.Pending)

        assertEquals(
            LabelScanAction.Collecting(BbqrProgress(1, 3)),
            scan(frames[0])
        )
    }

    @Test
    fun bbqrProgressCountsUniquePartsAndResetsOnANewSeries() {
        val session = BbqrAccumulator()
        assertEquals(
            BbqrReceive.Pending(BbqrProgress(1, 3), storedNewPart = true),
            session.receive("B\$HU03000001")
        )
        assertEquals(
            BbqrReceive.Pending(
                BbqrProgress(1, 3),
                storedNewPart = false,
                skip = BbqrSkip.Held,
                partIndex = 0,
            ),
            session.receive("B\$HU03000001")
        )
        assertEquals(listOf(1, 2), session.missingIndexes())
        assertEquals(
            BbqrReceive.Pending(BbqrProgress(2, 3), storedNewPart = true),
            session.receive("B\$HU03027FFF")
        )
        assertEquals(listOf(1), session.missingIndexes())
        // First six characters differ (B$HU03 vs B$HU02): the count restarts.
        assertEquals(
            BbqrReceive.Pending(BbqrProgress(1, 2), storedNewPart = true),
            session.receive("B\$HU020041")
        )
        assertEquals(BbqrProgress(1, 2), session.progress())

        val sheet = BbqrAccumulator()
        assertEquals(
            LabelScanAction.Collecting(BbqrProgress(1, 3)),
            labelScanAction("B\$HU03000001", sheet)
        )
        assertEquals(
            LabelScanAction.Skipped(BbqrSkip.Held, partIndex = 0),
            labelScanAction("B\$HU03000001", sheet)
        )
        assertEquals(
            LabelScanAction.Collecting(BbqrProgress(2, 3)),
            labelScanAction("B\$HU03010210", sheet)
        )
        assertEquals(
            LabelScanAction.Collecting(BbqrProgress(1, 2)),
            labelScanAction("B\$HU020041", sheet)
        )

        val jsonl = "{\"type\":\"tx\",\"ref\":\"aa\",\"label\":\"hi\"}"
        val frames = encodeBbqr(
            jsonl.toByteArray(StandardCharsets.UTF_8),
            'H',
            parts = 2
        )
        assertEquals(2, frames.size)
        val importing = BbqrAccumulator()
        assertEquals(
            LabelScanAction.Collecting(BbqrProgress(1, 2)),
            scan(frames[0], importing)
        )
        assertEquals(listOf(1), importing.missingIndexes())
        assertEquals(
            LabelScanAction.Skipped(BbqrSkip.Held, partIndex = 0),
            scan(frames[0], importing)
        )
        assertEquals(
            LabelScanAction.Import(jsonl, BbqrProgress(2, 2)),
            scan(frames[1], importing)
        )
    }

    @Test
    fun conflictingPartIsVisibleAndRequiresRestart() {
        val line0 = "{\"type\":\"tx\",\"ref\":\"aa\",\"label\":\"one\"}\n"
        val line1 = "{\"type\":\"tx\",\"ref\":\"bb\",\"label\":\"two\"}\n"
        val other0 = "{\"type\":\"tx\",\"ref\":\"aa\",\"label\":\"OTHER\"}\n"
        val a0 = bbqrHeader('H', 'U', 2, 0) + hexEncode(line0)
        val a1 = bbqrHeader('H', 'U', 2, 1) + hexEncode(line1)
        val b0 = bbqrHeader('H', 'U', 2, 0) + hexEncode(other0)
        assertFalse(a0 == b0)

        val session = BbqrAccumulator()
        assertEquals(
            LabelScanAction.Collecting(BbqrProgress(1, 2)),
            scan(a0, session)
        )
        assertEquals(
            LabelScanAction.Skipped(BbqrSkip.Held, partIndex = 0),
            scan(a0, session)
        )
        val during = session.seriesId()
        assertEquals(
            BbqrReceive.Conflict(partIndex = 0, progress = BbqrProgress(0, 2)),
            session.receive(b0)
        )
        // A matching repeat is not a conflict, and the conflict itself does
        // not start a new series id. The complementary part must not splice.
        assertEquals(during, session.seriesId())
        assertEquals(BbqrProgress(0, 2), session.progress())
        assertTrue(session.missingIndexes().isEmpty())
        assertEquals(LabelScanAction.IgnoreAndResume, scan("UR:BYTES/1-2/ABCDEF", session))
        assertEquals(LabelScanAction.IgnoreAndResume, scan("BQR:1/1/T/QUJD", session))
        assertEquals(
            LabelScanAction.Skipped(BbqrSkip.Index, partIndex = 5),
            scan("B\$HU0205FFFF", session)
        )
        assertEquals(
            LabelScanAction.Skipped(BbqrSkip.Conflict, partIndex = 1),
            scan(a1, session)
        )
        assertEquals(
            LabelScanAction.Restart(BBQR_CONFLICT_MESSAGE, partIndex = 0),
            scan(b0, BbqrAccumulator().also { it.receive(a0) })
        )

        session.reset()
        assertTrue(session.seriesId() != during)
        assertEquals(
            LabelScanAction.Collecting(BbqrProgress(1, 2)),
            scan(a0, session)
        )
        assertEquals(
            LabelScanAction.Import(line0 + line1, BbqrProgress(2, 2)),
            scan(a1, session)
        )
        // A finished series does not import again, even if a later body disagrees.
        assertEquals(
            LabelScanAction.Skipped(BbqrSkip.Closed, partIndex = 0),
            scan(b0, session)
        )
        assertEquals(
            LabelScanAction.Skipped(BbqrSkip.Closed, partIndex = 0),
            scan(a0, session)
        )

        // Accumulator only. A different six-character signature still
        // replaces the locked series. The sheet gate ignores that frame
        // while the conflict dialog is up.
        val locked = BbqrAccumulator()
        assertEquals(LabelScanAction.Collecting(BbqrProgress(1, 2)), scan(a0, locked))
        assertTrue(scan(b0, locked) is LabelScanAction.Restart)
        val text = "{\"type\":\"tx\",\"ref\":\"aa\",\"label\":\"hi\"}"
        val frame = encodeBbqr(text.toByteArray(StandardCharsets.UTF_8), 'H', parts = 1).single()
        assertEquals(
            LabelScanAction.Import(text, BbqrProgress(1, 1)),
            scan(frame, locked)
        )
    }

    @Test
    fun conflictDialogGatesReceivesUntilRestart() {
        val line0 = "{\"type\":\"tx\",\"ref\":\"aa\",\"label\":\"one\"}\n"
        val line1 = "{\"type\":\"tx\",\"ref\":\"bb\",\"label\":\"two\"}\n"
        val other0 = "{\"type\":\"tx\",\"ref\":\"aa\",\"label\":\"OTHER\"}\n"
        val a0 = bbqrHeader('H', 'U', 2, 0) + hexEncode(line0)
        val a1 = bbqrHeader('H', 'U', 2, 1) + hexEncode(line1)
        val b0 = bbqrHeader('H', 'U', 2, 0) + hexEncode(other0)
        val text = "{\"type\":\"tx\",\"ref\":\"aa\",\"label\":\"hi\"}"
        val otherSeries = encodeBbqr(
            text.toByteArray(StandardCharsets.UTF_8),
            'H',
            parts = 1
        ).single()
        assertFalse(otherSeries.startsWith(a0.take(6)))

        val gate = LabelScanGate()
        assertFalse(gate.conflictDialogShowing())
        assertEquals(
            LabelScanAction.Collecting(BbqrProgress(1, 2)),
            gate.receive(a0)
        )
        val during = gate.bbqr.seriesId()
        assertEquals(
            LabelScanAction.Restart(BBQR_CONFLICT_MESSAGE, partIndex = 0),
            gate.receive(b0)
        )
        assertTrue(gate.conflictDialogShowing())
        assertEquals(during, gate.bbqr.seriesId())
        assertEquals(BbqrProgress(0, 2), gate.bbqr.progress())
        assertTrue(gate.bbqr.missingIndexes().isEmpty())

        // Same signature, the disagreeing part, a one-part export with a
        // different signature, plain JSONL, and a UR frame are all ignored.
        assertNull(gate.receive(a0))
        assertNull(gate.receive(a1))
        assertNull(gate.receive(b0))
        assertNull(gate.receive(otherSeries))
        assertNull(gate.receive(text))
        assertNull(gate.receive("UR:BYTES/1-2/ABCDEF"))
        assertEquals(during, gate.bbqr.seriesId())
        assertEquals(BbqrProgress(0, 2), gate.bbqr.progress())
        assertTrue(gate.bbqr.missingIndexes().isEmpty())

        // The accumulator on its own would still open that other series.
        val unlocked = BbqrAccumulator()
        assertEquals(LabelScanAction.Collecting(BbqrProgress(1, 2)), scan(a0, unlocked))
        assertTrue(scan(b0, unlocked) is LabelScanAction.Restart)
        assertEquals(
            LabelScanAction.Import(text, BbqrProgress(1, 1)),
            scan(otherSeries, unlocked)
        )

        gate.restart()
        assertFalse(gate.conflictDialogShowing())
        assertTrue(gate.bbqr.seriesId() != during)
        assertEquals(
            LabelScanAction.Collecting(BbqrProgress(1, 2)),
            gate.receive(a0)
        )
        assertEquals(
            LabelScanAction.Import(line0 + line1, BbqrProgress(2, 2)),
            gate.receive(a1)
        )
        // The import consumed the scan. Another signature cannot finish behind it.
        assertNull(gate.receive(otherSeries))
    }

    @Test
    fun sheetStopsScannerWhileConflictDialogIsUp() {
        val sheet = source(
            "app/src/main/java/com/samourai/sentinel/ui/fragments/ImportLabelsScanBottomSheet.kt"
        )
        val listener = sheet.substringAfter("setOnScanListener").substringBefore("setURPayloadDecoder")
        assertTrue(listener.contains("receiveLabelFrame(payload, labelGate)"))
        val restartArm = sheet.substringAfter("is LabelScanAction.Restart ->")
            .substringBefore("LabelScanAction.IgnoreAndResume")
        assertTrue(restartArm.contains("stopScanner()"))
        assertTrue(restartArm.contains("showConflictDialog()"))
        val startOver = sheet.substringAfter("fun restartBbqrScan()")
            .substringBefore("fun labelsFromUR")
        assertTrue(startOver.contains("labelGate.restart()"))
        assertTrue(startOver.contains("resumeScan()"))
        assertTrue(startOver.contains("bbqrGeneration += 1"))
    }

    @Test
    fun garbledFrameIsSkippedAndALaterPartCanComplete() {
        assertEquals(
            LabelScanAction.Skipped(BbqrSkip.Decode, partIndex = 0, thisSeries = false),
            scan("B\$HU02000G")
        )
        assertEquals(
            LabelScanAction.Skipped(BbqrSkip.Index, partIndex = 5, thisSeries = false),
            scan("B\$HU0305FFFF")
        )

        val jsonl = "{\"type\":\"tx\",\"ref\":\"aa\",\"label\":\"hi\"}"
        val frames = encodeBbqr(
            jsonl.toByteArray(StandardCharsets.UTF_8),
            'H',
            parts = 2
        )
        assertEquals(2, frames.size)
        val session = BbqrAccumulator()
        assertEquals(
            LabelScanAction.Collecting(BbqrProgress(1, 2)),
            scan(frames[0], session)
        )

        assertEquals(listOf(1), session.missingIndexes())
        val badNewPart = frames[1].take(BBQR_HEADER_LENGTH) + "0G"
        assertEquals(
            LabelScanAction.Skipped(BbqrSkip.Decode, partIndex = 1),
            scan(badNewPart, session)
        )
        assertEquals(BbqrProgress(1, 2), session.progress())
        assertEquals(listOf(1), session.missingIndexes())

        val unaddressable = frames[0].take(6) + "05FFFF"
        assertEquals(
            LabelScanAction.Skipped(BbqrSkip.Index, partIndex = 5),
            scan(unaddressable, session)
        )
        assertEquals(BbqrProgress(1, 2), session.progress())

        // Undecodable body, different six-character signature: the series in progress stays.
        assertEquals(
            LabelScanAction.Skipped(BbqrSkip.Decode, partIndex = 0, thisSeries = false),
            scan("B\$2U0300A", session)
        )
        assertEquals(BbqrProgress(1, 2), session.progress())
        assertEquals(listOf(1), session.missingIndexes())

        assertEquals(
            LabelScanAction.Import(jsonl, BbqrProgress(2, 2)),
            scan(frames[1], session)
        )
        assertEquals(
            LabelScanAction.Skipped(BbqrSkip.Closed, partIndex = 1),
            scan(frames[1], session)
        )
        assertEquals(
            LabelScanAction.Skipped(BbqrSkip.Closed, partIndex = 0),
            scan(frames[0], session)
        )
    }

    @Test
    fun onePartBase32DecodesRfc4648Vectors() {
        val vectors = listOf(
            "" to "",
            "f" to "MY",
            "fo" to "MZXQ",
            "foo" to "MZXW6",
            "foob" to "MZXW6YQ",
            "fooba" to "MZXW6YTB",
            "foobar" to "MZXW6YTBOI",
        )
        for ((raw, encoded) in vectors) {
            val done = BbqrAccumulator().receive("B\$2U0100$encoded")
            assertTrue(done is BbqrReceive.Complete)
            assertArrayEquals(
                raw.toByteArray(StandardCharsets.UTF_8),
                (done as BbqrReceive.Complete).bytes
            )
        }
    }

    @Test
    fun zlibPartsInflateARawDeflateStream() {
        // zlib.compressobj(wbits=-10) of the withdrawal line below, then
        // unpadded base32. Split on an 8-character boundary so each part
        // is its own quantum. This is the Coinkite Z encoding, not Java's
        // default window.
        val expected = (
            "{\"type\":\"tx\",\"ref\":\"c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0" +
                "c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0\",\"label\":\"withdrawal\"}\n"
            ).toByteArray(StandardCharsets.UTF_8)
        val body =
            "VNLCVKJMJBK3EURKVFINEUJKJJGQGMUTJVJ4YUWN2MWBELMTBSJA2U4MKKG5GTAS" +
                "JWJ4ZEWNKMWFELOTBQEMSAYNZJEUYSWNAENFLHSZSKIVFFCYT2MKGVGLAUAA"
        val onePart = BbqrAccumulator().receive("B\$ZU0100$body")
        assertTrue(onePart is BbqrReceive.Complete)
        assertArrayEquals(expected, (onePart as BbqrReceive.Complete).bytes)

        val head = body.substring(0, 120)
        val tail = body.substring(120)
        val session = BbqrAccumulator()
        assertTrue(session.receive("B\$ZU0201$tail") is BbqrReceive.Pending)
        assertTrue(session.receive("B\$ZU0201$tail") is BbqrReceive.Pending)
        val complete = session.receive("B\$ZU0200$head")
        assertTrue(complete is BbqrReceive.Complete)
        assertArrayEquals(expected, (complete as BbqrReceive.Complete).bytes)
    }

    @Test
    fun bbqrLabelsJsonlImportsLikeTheFile() {
        val text = fixture().joinToString("\n")
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        val cases = listOf(
            encodeBbqr(bytes, 'Z', parts = 1),
            encodeBbqr(bytes, 'Z', parts = 3),
            encodeBbqr(bytes, 'H', parts = 3),
            encodeBbqr(bytes, '2', parts = 2),
            encodeBbqr(bytes, 'H', parts = 1, fileType = 'J'),
        )
        assertTrue(cases[1].size > 1)
        assertTrue(cases[2].size > 1)
        for (frames in cases) {
            val jsonl = collectJsonl(frames)
            assertEquals(text, jsonl)
            assertEquals(importFresh(text), importFresh(jsonl))
        }
    }

    @Test
    fun bbqrRejectsPayloadThatIsNotLabelsJsonl() {
        val text = "not json at all"
        for (encoding in listOf('H', '2', 'Z')) {
            val frames = encodeBbqr(text.toByteArray(StandardCharsets.UTF_8), encoding, parts = 1)
            assertEquals(
                LabelScanAction.Notify(BBQR_NOT_LABELS_MESSAGE),
                scan(frames.single())
            )
        }
        // Empty base32 is a real payload of zero bytes, not a broken frame.
        assertEquals(
            LabelScanAction.Notify(BBQR_NOT_LABELS_MESSAGE),
            scan("B\$2U0100")
        )
        val session = BbqrAccumulator()
        val part = encodeBbqr(
            "not json at all".toByteArray(StandardCharsets.UTF_8),
            'H',
            parts = 2
        )
        assertTrue(part.size > 1)
        assertEquals(
            LabelScanAction.Collecting(BbqrProgress(1, part.size)),
            scan(part[0], session)
        )
        assertEquals(
            LabelScanAction.Notify(BBQR_NOT_LABELS_MESSAGE),
            scan(part[1], session)
        )
        assertEquals(
            LabelScanAction.Skipped(BbqrSkip.Closed, partIndex = part.lastIndex),
            scan(part[1], session)
        )
    }

    @Test
    fun bqrPrefixDoesNotJoinABbqrSeries() {
        val session = BbqrAccumulator()
        assertEquals(
            LabelScanAction.Collecting(BbqrProgress(1, 2)),
            scan("B\$HU020041", session)
        )
        assertEquals(LabelScanAction.IgnoreAndResume, scan("BQR:1/1/T/QUJD", session))
        assertEquals(LabelScanAction.IgnoreAndResume, scan("UR:BYTES/1-2/ABCDEF", session))
        // Part 1 completes only if the BQR: and UR frames did not reset the series.
        assertEquals(
            LabelScanAction.Notify(BBQR_NOT_LABELS_MESSAGE),
            scan("B\$HU020142", session)
        )
    }

    @Test
    fun aNewBbqrSeriesReplacesAnIncompleteOne() {
        val session = BbqrAccumulator()
        assertEquals(
            LabelScanAction.Collecting(BbqrProgress(1, 2)),
            scan("B\$HU020041", session)
        )
        val text = "{\"type\":\"tx\",\"ref\":\"aa\",\"label\":\"hi\"}"
        val frame = encodeBbqr(text.toByteArray(StandardCharsets.UTF_8), 'H', parts = 1).single()
        assertEquals(
            LabelScanAction.Import(text, BbqrProgress(1, 1)),
            scan(frame, session)
        )
    }

    @Test
    fun bbqrRejectsUnsupportedFileTypes() {
        val labels = fixture().joinToString("\n").toByteArray(StandardCharsets.UTF_8)
        val psbtShaped = encodeBbqr(labels, 'H', parts = 1, fileType = 'P').single()
        assertEquals(
            LabelScanAction.Notify(bbqrFileTypeMessage('P')),
            scan(psbtShaped)
        )
        for (type in listOf('P', 'T', 'C', 'B', 'X', 'S', 'R', 'E', '1')) {
            val session = BbqrAccumulator()
            assertEquals(
                LabelScanAction.Notify(bbqrFileTypeMessage(type)),
                scan("B\$H${type}0200AA", session)
            )
            assertEquals(
                LabelScanAction.Skipped(BbqrSkip.Closed, partIndex = 1),
                scan("B\$H${type}0201BB", session)
            )
        }
    }

    @Test
    fun bbqrErrorsUseTheSpecSpelling() {
        val messages = listOf(
            BBQR_DECODE_FAILED_MESSAGE,
            BBQR_NOT_LABELS_MESSAGE,
            bbqrFileTypeMessage('P'),
            BBQR_CONFLICT_MESSAGE,
        )
        for (message in messages) {
            assertTrue(message.contains("BBQr"))
            assertFalse(message.contains("BBQR"))
            assertFalse(message.contains("BQR:"))
            assertFalse(message.contains("not yet supported"))
        }
        assertEquals(
            "BBQr parts disagree. Start the scan over.",
            BBQR_CONFLICT_MESSAGE
        )
        assertEquals("Start over", BBQR_START_OVER)
        assertFalse(BBQR_START_OVER.contains("BBQR"))
        assertFalse(BBQR_START_OVER.contains("BQR:"))
        assertEquals(
            "BBQr label export could not be decoded",
            BBQR_DECODE_FAILED_MESSAGE
        )
        assertEquals(
            "BBQr payload is not a label export",
            BBQR_NOT_LABELS_MESSAGE
        )
        assertEquals(
            "BBQr file type P is not a label export",
            bbqrFileTypeMessage('P')
        )
    }

    @Test
    fun cancelledFinishRollsBackTheCompletingPart() {
        val text = "{\"type\":\"tx\",\"ref\":\"aa\",\"label\":\"hi\"}"
        val frames = encodeBbqr(text.toByteArray(StandardCharsets.UTF_8), 'H', parts = 2)
        val session = BbqrAccumulator()
        assertTrue(session.receive(frames[0]) is BbqrReceive.Pending)
        var calls = 0
        try {
            session.receive(frames[1]) {
                calls += 1
                calls < 2
            }
            fail("expected the finish to abandon")
        } catch (_: BbqrAbandoned) {
            // The completing part is not kept, so the series can finish later.
        }
        assertEquals(1, session.progress().collected)
        assertEquals(
            LabelScanAction.Import(text, BbqrProgress(2, 2)),
            scan(frames[1], session)
        )
    }

    @Test
    fun finishRunsOffTheCallerThread() = runBlocking {
        val caller = Thread.currentThread().id
        val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            val text = "{\"type\":\"tx\",\"ref\":\"aa\",\"label\":\"hi\"}"
            val frame = encodeBbqr(text.toByteArray(StandardCharsets.UTF_8), 'Z', parts = 1).single()
            var ranOn = -1L
            val action = runLabelFrame(frame, BbqrAccumulator(), worker = worker) {
                ranOn = Thread.currentThread().id
                true
            }
            assertTrue(ranOn != caller)
            assertEquals(LabelScanAction.Import(text, BbqrProgress(1, 1)), action)
        } finally {
            worker.close()
        }
    }

    @Test
    fun decodedCapAcceptsTheBoundaryAndRejectsOnePastIt() {
        val exactBody = CharArray(BBQR_MAX_DECODED_BYTES * 2) { '0' }.concatToString()
        val exact = BbqrAccumulator().receive(bbqrHeader('H', 'U', 1, 0) + exactBody)
        assertTrue(exact is BbqrReceive.Complete)
        assertEquals(BBQR_MAX_DECODED_BYTES, (exact as BbqrReceive.Complete).bytes.size)

        val overBody = CharArray((BBQR_MAX_DECODED_BYTES + 1) * 2) { 'A' }.concatToString()
        val session = BbqrAccumulator()
        val over = session.receive(bbqrHeader('H', 'U', 1, 0) + overBody)
        assertTrue(over is BbqrReceive.Rejected)
        assertEquals(0, session.progress().collected)
        assertEquals(
            LabelScanAction.Skipped(BbqrSkip.Closed, partIndex = 0),
            scan(bbqrHeader('H', 'U', 1, 0) + "00", session)
        )
    }

    @Test
    fun aggregateCapStopsALargeIncompleteSeries() {
        val partBytes = 256 * 1024
        val fitting = BBQR_MAX_DECODED_BYTES / partBytes
        assertEquals(16, fitting)
        assertEquals(BBQR_MAX_DECODED_BYTES, fitting * partBytes)
        val count = fitting + 4
        val body = CharArray(partBytes * 2) { '0' }.concatToString()
        val session = BbqrAccumulator()
        for (index in 0 until fitting) {
            assertTrue(
                session.receive(bbqrHeader('H', 'U', count, index) + body) is BbqrReceive.Pending
            )
        }
        assertEquals(BbqrProgress(fitting, count), session.progress())
        val overflow = session.receive(bbqrHeader('H', 'U', count, fitting) + "FF")
        assertTrue(overflow is BbqrReceive.Rejected)
        assertEquals(BbqrProgress(0, count), session.progress())
        assertEquals(
            LabelScanAction.Skipped(BbqrSkip.Closed, partIndex = 0),
            scan(bbqrHeader('H', 'U', count, 0) + "00", session)
        )
    }

    @Test
    fun garbledOrForeignOverCapBodyDoesNotLockAnOpenSeries() {
        val session = BbqrAccumulator()
        assertEquals(
            LabelScanAction.Collecting(BbqrProgress(1, 2)),
            scan("B\$HU020041", session)
        )
        val garbled = CharArray(BBQR_MAX_ENCODED_CHARS + 2) { 'A' }
        garbled[0] = 'G'
        assertEquals(
            LabelScanAction.Skipped(BbqrSkip.Decode, partIndex = 1),
            scan(bbqrHeader('H', 'U', 2, 1) + String(garbled), session)
        )
        assertEquals(BbqrProgress(1, 2), session.progress())

        val huge = CharArray((BBQR_MAX_DECODED_BYTES + 1) * 2) { 'A' }.concatToString()
        assertEquals(
            LabelScanAction.Skipped(BbqrSkip.Decode, partIndex = 0, thisSeries = false),
            scan(bbqrHeader('H', 'U', 1, 0) + huge, session)
        )
        assertEquals(BbqrProgress(1, 2), session.progress())
        assertEquals(
            LabelScanAction.Notify(BBQR_NOT_LABELS_MESSAGE),
            scan("B\$HU020142", session)
        )
    }

    @Test
    fun inflateCapStopsAHighlyCompressibleStream() {
        val exact = ByteArray(BBQR_MAX_DECODED_BYTES)
        val exactCompressed = deflateRaw(exact)
        assertTrue(exactCompressed.size < 64 * 1024)
        val exactDone = BbqrAccumulator().receive(
            encodeBbqr(exact, 'Z', parts = 1).single()
        )
        assertTrue(exactDone is BbqrReceive.Complete)
        val exactBytes = (exactDone as BbqrReceive.Complete).bytes
        assertEquals(BBQR_MAX_DECODED_BYTES, exactBytes.size)
        assertEquals(0, exactBytes[0].toInt())
        assertEquals(0, exactBytes[exactBytes.lastIndex].toInt())

        val over = ByteArray(BBQR_MAX_DECODED_BYTES + 1)
        val overCompressed = deflateRaw(over)
        assertTrue(overCompressed.size < 64 * 1024)
        val overDone = BbqrAccumulator().receive(encodeBbqr(over, 'Z', parts = 1).single())
        assertTrue(overDone is BbqrReceive.Rejected)
        assertFalse(overDone is BbqrReceive.Complete)
    }

    @Test
    fun rawDeflateRejectsUnconsumedInput() {
        val raw = "{\"type\":\"tx\",\"ref\":\"aa\",\"label\":\"hi\"}".toByteArray(StandardCharsets.UTF_8)
        val compressed = deflateRaw(raw)
        val clean = BbqrAccumulator().receive(zFrame(compressed))
        assertTrue(clean is BbqrReceive.Complete)
        assertArrayEquals(raw, (clean as BbqrReceive.Complete).bytes)

        assertTrue(
            BbqrAccumulator().receive(zFrame(compressed + byteArrayOf(0))) is BbqrReceive.Rejected
        )
        assertTrue(
            BbqrAccumulator().receive(zFrame(compressed + byteArrayOf(0x7f))) is BbqrReceive.Rejected
        )
        val concatenated = BbqrAccumulator().receive(zFrame(compressed + compressed))
        assertTrue(concatenated is BbqrReceive.Rejected)
        assertFalse(concatenated is BbqrReceive.Complete)

        assertTrue(
            BbqrAccumulator().receive(zFrame(deflateZlib(raw))) is BbqrReceive.Rejected
        )
        assertTrue(
            BbqrAccumulator().receive(
                zFrame(byteArrayOf(0x1f, 0x8b.toByte(), 0x08, 0x00, 0x00, 0x00, 0x00, 0x00))
            ) is BbqrReceive.Rejected
        )
        val truncated = compressed.copyOf(compressed.size / 2)
        assertTrue(truncated.isNotEmpty())
        assertTrue(truncated.size < compressed.size)
        assertTrue(BbqrAccumulator().receive(zFrame(truncated)) is BbqrReceive.Rejected)
        assertTrue(
            BbqrAccumulator().receive(zFrame(byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)))
                is BbqrReceive.Rejected
        )
    }

    private fun scan(
        payload: String,
        session: BbqrAccumulator = BbqrAccumulator(),
    ): LabelScanAction = labelScanAction(payload, session)

    private fun source(relative: String): String {
        val file = sequenceOf(
            File(relative),
            File(relative.removePrefix("app/")),
            File("../$relative"),
        ).firstOrNull { it.isFile }
            ?: error("missing $relative from ${File(".").absolutePath}")
        return file.readText()
    }

    private fun importFresh(text: String): ImportResult = runBlocking {
        freshImporter().import(BufferedReader(StringReader(text)), "mainnet", 1000L)
    }

    /**
     * Feeds [frames] out of order, with the first part repeated, and
     * returns the single imported JSONL. A second copy of a finished
     * one-part frame must not import again.
     */
    private fun collectJsonl(frames: List<String>): String {
        val session = BbqrAccumulator()
        val order = ArrayList<String>()
        if (frames.size == 1) {
            order.add(frames[0])
        } else {
            order.add(frames.last())
            order.add(frames.first())
            order.add(frames.first())
            for (index in 1 until frames.lastIndex) order.add(frames[index])
        }
        var jsonl: String? = null
        for (frame in order) {
            when (val action = labelScanAction(frame, session)) {
                is LabelScanAction.Import -> {
                    check(jsonl == null) { "BBQr series imported twice" }
                    jsonl = action.jsonl
                }
                is LabelScanAction.Collecting -> Unit
                is LabelScanAction.Skipped -> Unit
                is LabelScanAction.Restart -> error(action.message)
                LabelScanAction.IgnoreAndResume -> Unit
                is LabelScanAction.Notify -> error(action.message)
            }
        }
        if (frames.size == 1) {
            assertEquals(
                LabelScanAction.Skipped(BbqrSkip.Closed, partIndex = 0),
                labelScanAction(frames[0], session)
            )
        }
        return checkNotNull(jsonl)
    }

    private fun encodeBbqr(
        raw: ByteArray,
        encoding: Char,
        parts: Int,
        fileType: Char = 'U',
    ): List<String> {
        val encoded = when (encoding) {
            'H' -> raw.joinToString("") { "%02X".format(it) }
            '2' -> base32(raw)
            'Z' -> base32(deflateRaw(raw))
            else -> error("encoding $encoding")
        }
        val mod = if (encoding == 'H') 2 else 8
        val chunks = splitEncoded(encoded, parts, mod)
        return chunks.mapIndexed { index, chunk ->
            bbqrHeader(encoding, fileType, chunks.size, index) + chunk
        }
    }

    private fun bbqrHeader(encoding: Char, fileType: Char, count: Int, index: Int): String {
        fun base36(n: Int) = n.toString(36).uppercase().padStart(2, '0')
        return "B$$encoding$fileType${base36(count)}${base36(index)}"
    }

    private fun splitEncoded(encoded: String, parts: Int, mod: Int): List<String> {
        if (parts <= 1 || encoded.length <= mod) return listOf(encoded)
        var chunk = (encoded.length / parts / mod) * mod
        if (chunk < mod) chunk = mod
        val out = ArrayList<String>()
        var offset = 0
        while (offset < encoded.length) {
            val remaining = encoded.length - offset
            val last = out.size == parts - 1 || remaining <= chunk
            val end = if (last) encoded.length else offset + chunk
            out.add(encoded.substring(offset, end))
            offset = end
            if (last) break
        }
        return out
    }

    private fun base32(data: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val out = StringBuilder()
        var acc = 0
        var bits = 0
        for (b in data) {
            acc = (acc shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                out.append(alphabet[(acc shr bits) and 0x1F])
            }
            acc = acc and ((1 shl bits) - 1)
        }
        if (bits > 0) out.append(alphabet[(acc shl (5 - bits)) and 0x1F])
        return out.toString()
    }

    private fun hexEncode(text: String): String =
        text.toByteArray(StandardCharsets.UTF_8).joinToString("") { "%02X".format(it) }

    /** One Z frame around bytes that are already raw-deflated. */
    private fun zFrame(compressed: ByteArray): String =
        bbqrHeader('Z', 'U', 1, 0) + base32(compressed)

    private fun deflateRaw(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        return try {
            deflater.setInput(data)
            deflater.finish()
            val out = ByteArrayOutputStream()
            val buf = ByteArray(1024)
            while (!deflater.finished()) {
                val n = deflater.deflate(buf)
                if (n == 0) error("deflate stalled")
                out.write(buf, 0, n)
            }
            out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun deflateZlib(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION, false)
        return try {
            deflater.setInput(data)
            deflater.finish()
            val out = ByteArrayOutputStream()
            val buf = ByteArray(1024)
            while (!deflater.finished()) {
                val n = deflater.deflate(buf)
                if (n == 0) error("deflate stalled")
                out.write(buf, 0, n)
            }
            out.toByteArray()
        } finally {
            deflater.end()
        }
    }
}
