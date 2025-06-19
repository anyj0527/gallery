package com.google.ai.edge.gallery.ui.llmchat

import android.content.Context
import com.google.ai.edge.gallery.common.CleanUpListener
import com.google.ai.edge.gallery.common.LlmRuntime
import com.google.ai.edge.gallery.common.ResultListener
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.RuntimeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.*

class LlmChatModelHelperTest {

    private lateinit var mockContext: Context
    private lateinit var liteRtModel: Model
    private lateinit var dummyModel: Model

    @Before
    fun setUp() {
        mockContext = mock(Context::class.java)

        liteRtModel = Model(
            name = "LiteTestModel",
            version = "1",
            downloadFileName = "lite.tflite",
            url = "http://example.com/lite.tflite",
            sizeInBytes = 100L,
            runtimeType = RuntimeType.LITE_RT,
            llmSupportImage = true // Added for LiteRT case in initialize
        )
        dummyModel = Model(
            name = "DummyTestModel",
            version = "1",
            downloadFileName = "dummy.tflite",
            url = "http://example.com/dummy.tflite",
            sizeInBytes = 100L,
            runtimeType = RuntimeType.DUMMY
        )
    }

    @Test
    fun initialize_delegatesToCorrectRuntimeAndSetsInstance() {
        // Test with Dummy Model
        var dummyInitDone = false
        LlmChatModelHelper.initialize(mockContext, dummyModel) { errorMsg ->
            assertEquals("", errorMsg)
            dummyInitDone = true
        }
        assertTrue(dummyInitDone)
        assertNotNull("Dummy model instance should be set", dummyModel.instance)
        assertEquals("DummyInstanceInitialized", dummyModel.instance)

        // Test with LiteRT Model - This will try to actually initialize LiteRT.
        // This part of the test is more of an integration test and might fail if LiteRT
        // dependencies or native libraries are not available in the test environment.
        // We expect it to call LiteRtRuntime().initialize which might throw an error
        // if the .task file isn't found or native libs are missing.
        // The goal here is to ensure LlmChatModelHelper *tries* to use LiteRtRuntime.
        var liteRtInitError: String? = null
        LlmChatModelHelper.initialize(mockContext, liteRtModel) { errorMsg ->
            liteRtInitError = errorMsg
        }
        // We expect an error because the model file "lite.tflite" doesn't actually exist for LiteRT.
        // The key is that model.instance might be null OR an error message is returned.
        // If an error occurs, model.instance might not be set by LiteRtRuntime.
        assertTrue(
            "LiteRT initialization should either set instance or return an error. Error: $liteRtInitError",
            liteRtModel.instance != null || (liteRtInitError != null && liteRtInitError!!.isNotEmpty())
        )
        if (liteRtModel.instance != null) {
             assertTrue(liteRtModel.instance is com.google.ai.edge.gallery.common.LiteRtRuntime.LiteRtModelInstance)
        }

        // Cleanup
        LlmChatModelHelper.cleanUp(dummyModel)
        if (liteRtModel.instance != null) { // Only cleanup if instance was set
            LlmChatModelHelper.cleanUp(liteRtModel)
        }
    }

    @Test
    fun generateResponse_delegatesToDummyRuntime() {
        var cleanUpListenerCalled = false
        var resultListenerCalled = false

        LlmChatModelHelper.initialize(mockContext, dummyModel) {} // Initialize dummy
        assertNotNull(dummyModel.instance)

        LlmChatModelHelper.generateResponse(
            model = dummyModel,
            input = "test input",
            images = emptyList(),
            resultListener = { result, done ->
                resultListenerCalled = true
                assertTrue(result.startsWith("Echo from ${dummyModel.name} (Dummy):"))
                if (done) {
                    assertEquals("Echo from ${dummyModel.name} (Dummy): test input", result)
                }
            },
            cleanUpListener = {
                cleanUpListenerCalled = true
            }
        )

        Thread.sleep(1000) // DummyRuntime uses coroutines with delays

        assertTrue("ResultListener should be called for DummyRuntime", resultListenerCalled)
        assertTrue("CleanUpListener should be called for DummyRuntime", cleanUpListenerCalled)
        LlmChatModelHelper.cleanUp(dummyModel)
    }

    @Test
    fun resetSession_delegatesToDummyRuntime() {
        LlmChatModelHelper.initialize(mockContext, dummyModel) {}
        assertNotNull(dummyModel.instance)
        // No direct output to check, just ensure it doesn't crash
        LlmChatModelHelper.resetSession(dummyModel)
        LlmChatModelHelper.cleanUp(dummyModel)
    }

    @Test
    fun cleanUp_delegatesToDummyRuntimeAndNullifiesInstance() {
        LlmChatModelHelper.initialize(mockContext, dummyModel) {}
        assertNotNull(dummyModel.instance)
        LlmChatModelHelper.cleanUp(dummyModel)
        assertNull("Model instance should be null after cleanup", dummyModel.instance)
    }

    @Test
    fun cancelGenerateResponse_delegatesToDummyRuntime() {
         LlmChatModelHelper.initialize(mockContext, dummyModel) {}
         assertNotNull(dummyModel.instance)
         // No direct output to check, just ensure it doesn't crash
         LlmChatModelHelper.cancelGenerateResponse(dummyModel)
         LlmChatModelHelper.cleanUp(dummyModel)
    }

    @Test
    fun sizeInTokens_delegatesToDummyRuntime() {
        LlmChatModelHelper.initialize(mockContext, dummyModel) {}
        assertNotNull(dummyModel.instance)
        val tokens = LlmChatModelHelper.sizeInTokens(dummyModel, "hello test")
        assertEquals(4, tokens) // Based on DummyRuntime's logic
        LlmChatModelHelper.cleanUp(dummyModel)
    }
}
