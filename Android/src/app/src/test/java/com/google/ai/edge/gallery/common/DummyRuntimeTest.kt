package com.google.ai.edge.gallery.common

import android.content.Context
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.RuntimeType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock

@ExperimentalCoroutinesApi
class DummyRuntimeTest {

    private lateinit var dummyRuntime: DummyRuntime
    private lateinit var mockContext: Context
    private lateinit var testModel: Model

    @Before
    fun setUp() {
        dummyRuntime = DummyRuntime()
        mockContext = mock(Context::class.java)
        testModel = Model(
            name = "Dummy Test Model",
            version = "1",
            downloadFileName = "dummy.tflite",
            url = "http://example.com/dummy.tflite",
            sizeInBytes = 100L,
            runtimeType = RuntimeType.DUMMY
        )
    }

    @Test
    fun initialize_setsModelInstance() {
        var onDoneCalled = false
        dummyRuntime.initialize(mockContext, testModel) { errorMsg ->
            assertEquals("", errorMsg)
            onDoneCalled = true
        }
        assertTrue(onDoneCalled)
        assertEquals("DummyInstanceInitialized", testModel.instance)
    }

    @Test
    fun createSession_returnsDummySession() {
        dummyRuntime.initialize(mockContext, testModel) {}
        val session = dummyRuntime.createSession(testModel)
        assertEquals("DummySessionFor-${testModel.name}", session)
    }

    @Test
    fun generateResponse_streamsAndCompletes() = runTest {
        val input = "hello"
        val expectedPrefix = "Echo from ${testModel.name} (Dummy): "
        val receivedResults = mutableListOf<Pair<String, Boolean>>()
        var cleanUpCalled = false

        dummyRuntime.initialize(mockContext, testModel) {}
        dummyRuntime.generateResponse(
            model = testModel,
            input = input,
            images = emptyList(),
            resultListener = { result, done ->
                receivedResults.add(Pair(result, done))
            },
            cleanUpListener = {
                cleanUpCalled = true
            }
        )

        kotlinx.coroutines.delay(1000)

        assertTrue("ResultListener should have been called", receivedResults.isNotEmpty())

        // Check intermediate results
        for (i in 0 until input.length) {
            val partialResponse = expectedPrefix + input.substring(0, i + 1)
            assertTrue("Should contain partial response: $partialResponse. Received: ${receivedResults.joinToString()}",
                       receivedResults.any { it.first == partialResponse && !it.second })
        }

        val finalResult = receivedResults.last()
        assertEquals(expectedPrefix + input, finalResult.first)
        assertTrue("Last result should be 'done'", finalResult.second)
        assertTrue("CleanUpListener should be called", cleanUpCalled)
    }

    @Test
    fun generateResponse_withEmptyInput_completesImmediately() = runTest {
        val input = ""
        val expectedResponse = "Echo from ${testModel.name} (Dummy): "
        var resultReceived: String? = null
        var isDone = false
        var cleanUpCalled = false

        dummyRuntime.initialize(mockContext, testModel) {}
        dummyRuntime.generateResponse(
            model = testModel,
            input = input,
            images = emptyList(),
            resultListener = { result, done ->
                resultReceived = result
                isDone = done
            },
            cleanUpListener = { cleanUpCalled = true }
        )
        kotlinx.coroutines.delay(300)

        assertEquals(expectedResponse, resultReceived)
        assertTrue(isDone)
        assertTrue(cleanUpCalled)
    }


    @Test
    fun cancelGenerateResponse_stopsStreamingAndCallsCleanup() = runTest {
        val input = "long input to test cancellation"
        val receivedResults = mutableListOf<Pair<String, Boolean>>()
        var cleanUpCalled = false

        dummyRuntime.initialize(mockContext, testModel) {}
        dummyRuntime.generateResponse(
            model = testModel,
            input = input,
            images = emptyList(),
            resultListener = { result, done ->
                receivedResults.add(Pair(result, done))
                if (receivedResults.size == 2) {
                    dummyRuntime.cancelGenerateResponse(testModel)
                }
            },
            cleanUpListener = {
                cleanUpCalled = true
            }
        )

        kotlinx.coroutines.delay(1000)

        assertTrue("ResultListener should have been called", receivedResults.isNotEmpty())
        val lastResult = receivedResults.last()
        assertTrue("Last result after cancellation should be marked as done", lastResult.second)
        assertTrue("Last result content was: ${lastResult.first}", lastResult.first.contains("(Cancelled)"))
        assertTrue("CleanUpListener should be called after cancellation", cleanUpCalled)
    }

    @Test
    fun resetSession_cancelsOngoingJob() = runTest {
        var cleanUpCalled = false
         dummyRuntime.initialize(mockContext, testModel) {}
        dummyRuntime.generateResponse(testModel, "test", emptyList(), { _, _ -> }, { cleanUpCalled = true })

        dummyRuntime.resetSession(testModel)
        kotlinx.coroutines.delay(200) // Increased delay for cancellation to occur reliably

        assertTrue("Cleanup should be called when session is reset during generation", cleanUpCalled)
    }

    @Test
    fun cleanUp_clearsModelInstanceAndCancelsJob() = runTest {
        var cleanUpCalled = false
        dummyRuntime.initialize(mockContext, testModel) {}
        assertNotNull(testModel.instance)
        dummyRuntime.generateResponse(testModel, "test", emptyList(), { _, _ -> }, { cleanUpCalled = true })

        dummyRuntime.cleanUp(testModel)
        kotlinx.coroutines.delay(200) // Increased delay for cancellation


        assertNull("Model instance should be null after cleanup", testModel.instance)
        assertTrue("Cleanup listener from generateResponse should be called during main cleanup", cleanUpCalled)
    }

    @Test
    fun sizeInTokens_returnsTextLengthApproximation() {
        // The dummy logic is: text.split(" ").sumOf { it.length / 4 + 1 }
        assertEquals(1, dummyRuntime.sizeInTokens(testModel, "")) // "" -> [""] -> (0/4 + 1) = 1
        assertEquals(1, dummyRuntime.sizeInTokens(testModel, "hi")) // "hi" -> ["hi"] -> (2/4 + 1) = 1
        assertEquals(2, dummyRuntime.sizeInTokens(testModel, "hello")) // "hello" -> ["hello"] -> (5/4+1) = 2
        assertEquals(4, dummyRuntime.sizeInTokens(testModel, "hello world")) // "hello world" -> ["hello", "world"] -> (5/4+1) + (5/4+1) = 2 + 2 = 4
        assertEquals(3, dummyRuntime.sizeInTokens(testModel, "a b c")) // "a b c" -> ["a","b","c"] -> (1/4+1)*3 = 1*3 = 3
    }
}
