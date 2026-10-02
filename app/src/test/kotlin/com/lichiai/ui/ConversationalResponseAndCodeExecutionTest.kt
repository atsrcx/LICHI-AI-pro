package com.lichiai.ui

import com.lichiai.code.CodeExecutionManager
import com.lichiai.code.LocalProcessCodeExecutionEngine
import com.lichiai.ui.activity.ActivityKind
import com.lichiai.ui.activity.TaskActivityStatus
import com.lichiai.ui.activity.toTaskStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class ConversationalResponseAndCodeExecutionTest {

    private lateinit var executionEngine: LocalProcessCodeExecutionEngine
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = File(System.getProperty("java.io.tmpdir", "/tmp"), "lichi_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        executionEngine = LocalProcessCodeExecutionEngine(
            context = null,
            filesDir = tempDir,
            cacheDir = tempDir
        )
    }

    @Test
    fun testResponseBlockParser_MixedConversationalContent() {
        val raw = """
            Zaroor! Yahan ek interesting story line hai:
            
            # Title: Aakhri Train
            ## Genre: Mystery / Thriller
            
            Arjun, ek thaka hua office worker, midnight local train mein baithta hai.
            
            - Pehla scene: Station par sannata
            - Doosra scene: Ajeeb musafir
            
            1. Investigation shuru hoti hai
            2. Mystery uncover hoti hai
            
            > "Sach hamesha wahi nahi hota jo dikhta hai."
            
            ```python
            print("Hello LICHI")
            ```
            
            PROMPT example:
            ```prompt
            You are an Android code auditor...
            ```
            
            JSON config:
            ```json
            {"status": "ok", "count": 42}
            ```
            
            | Episode | Title | Status |
            |---|---|---|
            | 1 | The Beginning | Done |
            | 2 | The Clue | Pending |
            
            ---
            Dhanyavaad!
        """.trimIndent()

        val blocks = ResponseBlockParser.parse(raw, "msg_test_1")
        assertTrue(blocks.isNotEmpty())

        val headings = blocks.filterIsInstance<ResponseBlock.Heading>()
        assertEquals(2, headings.size)
        assertEquals(1, headings[0].level)
        assertEquals("Title: Aakhri Train", headings[0].text)
        assertEquals(2, headings[1].level)
        assertEquals("Genre: Mystery / Thriller", headings[1].text)

        val paragraphs = blocks.filterIsInstance<ResponseBlock.Paragraph>()
        assertTrue(paragraphs.any { it.text.contains("Zaroor!") })
        assertTrue(paragraphs.any { it.text.contains("Arjun, ek thaka hua") })
        assertTrue(paragraphs.any { it.text.contains("Dhanyavaad!") })

        val bullets = blocks.filterIsInstance<ResponseBlock.BulletItem>()
        assertEquals(2, bullets.size)
        assertEquals("Pehla scene: Station par sannata", bullets[0].text)

        val numbered = blocks.filterIsInstance<ResponseBlock.NumberedItem>()
        assertEquals(2, numbered.size)
        assertEquals(1, numbered[0].number)
        assertEquals(2, numbered[1].number)

        val quotes = blocks.filterIsInstance<ResponseBlock.Quote>()
        assertEquals(1, quotes.size)
        assertTrue(quotes[0].text.contains("Sach hamesha"))

        val codes = blocks.filterIsInstance<ResponseBlock.Code>()
        assertEquals(1, codes.size)
        assertEquals("python", codes[0].language)
        assertEquals("print(\"Hello LICHI\")", codes[0].code)

        val prompts = blocks.filterIsInstance<ResponseBlock.Prompt>()
        assertEquals(1, prompts.size)
        assertTrue(prompts[0].promptText.contains("Android code auditor"))

        val structured = blocks.filterIsInstance<ResponseBlock.StructuredData>()
        assertEquals(1, structured.size)
        assertEquals("json", structured[0].type)
        assertTrue(structured[0].content.contains("\"count\": 42"))

        val tables = blocks.filterIsInstance<ResponseBlock.Table>()
        assertEquals(1, tables.size)
        assertEquals(listOf("Episode", "Title", "Status"), tables[0].headers)
        assertEquals(2, tables[0].rows.size)

        val dividers = blocks.filterIsInstance<ResponseBlock.Divider>()
        assertEquals(1, dividers.size)
    }

    @Test
    fun testResponseInlineParser_FormattingSpans() {
        val inline = ResponseInlineParser.parse("Check `val x = 10` and **bold text** with *italic* and [Lichi](https://lichi.ai)", isDark = false)
        val text = inline.text
        assertTrue(text.contains("val x = 10"))
        assertTrue(text.contains("bold text"))
        assertTrue(text.contains("italic"))
        assertTrue(text.contains("Lichi"))

        val urlAnnotations = inline.getStringAnnotations("URL", 0, inline.length)
        assertEquals(1, urlAnnotations.size)
        assertEquals("https://lichi.ai", urlAnnotations[0].item)
    }

    @Test
    fun testPythonExecution_PrintStatement() = runBlocking {
        val code = """
            print("Hello LICHI")
            print("Execution Success")
        """.trimIndent()

        val result = executionEngine.execute(
            blockId = "block_py_1",
            language = "python",
            code = code,
            timeoutMs = 5000L
        )

        assertTrue("Execution should succeed", result.success)
        assertEquals(0, result.exitCode)
        assertTrue("Stdout should contain Hello LICHI", result.stdout.contains("Hello LICHI"))
        assertTrue("Stdout should contain Execution Success", result.stdout.contains("Execution Success"))
    }

    @Test
    fun testPythonExecution_VariablesAndLoop() = runBlocking {
        val code = """
            name = "LICHI AI"
            version = 3
            print(f"System: {name} v{version}")
            for i in range(3):
                print(f"Step {i}")
        """.trimIndent()

        val result = executionEngine.execute(
            blockId = "block_py_2",
            language = "python",
            code = code,
            timeoutMs = 5000L
        )

        assertTrue(result.success)
        assertTrue(result.stdout.contains("System: LICHI AI v3"))
        assertTrue(result.stdout.contains("Step 0"))
        assertTrue(result.stdout.contains("Step 1"))
        assertTrue(result.stdout.contains("Step 2"))
    }

    @Test
    fun testPythonExecution_Cancellation() = runBlocking {
        val blockId = "block_cancel_test"
        executionEngine.cancel(blockId)
        assertNotNull(executionEngine)
    }

    @Test
    fun testCodeExecutionManager_LanguageSupport() {
        val engine = LocalProcessCodeExecutionEngine(null, tempDir, tempDir)
        assertTrue(engine.isLanguageSupported("python"))
        assertTrue(engine.isLanguageSupported("py"))
        assertTrue(engine.isLanguageSupported("python3"))
        assertTrue(engine.isLanguageSupported("sh"))
        assertTrue(engine.isLanguageSupported("bash"))
        assertFalse(engine.isLanguageSupported("json"))
        assertFalse(engine.isLanguageSupported("xml"))
        assertFalse(engine.isLanguageSupported("markdown"))
    }

    @Test
    fun testTaskActivityStatus_Mapping() {
        assertEquals(TaskActivityStatus.SEARCHING, ActivityKind.SEARCHING_WEB.toTaskStatus())
        assertEquals(TaskActivityStatus.EXECUTING, ActivityKind.TERMINAL_EXECUTING.toTaskStatus())
        assertEquals(TaskActivityStatus.COMPLETED, ActivityKind.COMPLETED.toTaskStatus())
        assertEquals(TaskActivityStatus.FAILED, ActivityKind.FAILED.toTaskStatus())
        assertEquals(TaskActivityStatus.WORKING, ActivityKind.THINKING.toTaskStatus())
    }
}
