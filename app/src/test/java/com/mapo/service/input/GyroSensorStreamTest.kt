package com.mapo.service.input

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Lifecycle + soft-degrade tests for [GyroSensorStream]. Plain JUnit + mockk
 * (no Robolectric needed — we mock at the SensorManager boundary). Construction
 * of a real `SensorEvent` for onSensorChanged testing requires Robolectric's
 * ShadowSensor + reflection on `SensorEvent`'s package-private constructor;
 * that's deferred to a device-test verification of the listener path. These
 * tests cover register / unregister / soft-degrade / idempotence only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GyroSensorStreamTest {

    private val testScope: TestScope = TestScope(UnconfinedTestDispatcher())
    private lateinit var context: Context
    private lateinit var sensorManager: SensorManager
    private lateinit var gyroSensor: Sensor
    private lateinit var inputEvaluator: InputEvaluator

    @Before
    fun setUp() {
        context = mockk()
        sensorManager = mockk(relaxed = true)
        gyroSensor = mockk(relaxed = true) {
            every { name } returns "Mock Gyro"
        }
        inputEvaluator = mockk(relaxed = true)
        every { context.getSystemService(Context.SENSOR_SERVICE) } returns sensorManager
        // Default: no rotation-vector sensor — these tests focus on gyro
        // lifecycle. Tests that exercise the tilt-companion path stub these
        // to non-null Sensors explicitly. Without these defaults the
        // `relaxed = true` mock would return a stub Sensor for both, and
        // `start()`'s second `registerListener` (for rotation) would
        // double the "register count" the lifecycle assertions check.
        every { sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) } returns null
        every { sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) } returns null
    }

    @After
    fun tearDown() {
        (testScope as CoroutineScope).cancel()
    }

    @Test
    fun noGyroSensor_hasGyroIsFalse_andStartReturnsFalse() {
        every { sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) } returns null
        val subject = GyroSensorStream(context, testScope, inputEvaluator)
        assertFalse(subject.hasGyro)
        assertFalse(subject.start())
        assertFalse(subject.isRunning)
    }

    @Test
    fun gyroPresent_startRegistersListenerAndIsRunning() {
        every { sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) } returns gyroSensor
        every { sensorManager.registerListener(any<SensorEventListener>(), any(), any<Int>()) } returns true
        val subject = GyroSensorStream(context, testScope, inputEvaluator)
        assertTrue(subject.hasGyro)
        assertTrue(subject.start())
        assertTrue(subject.isRunning)
        verify {
            sensorManager.registerListener(subject, gyroSensor, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    @Test
    fun start_idempotent_doesNotDoubleRegister() {
        every { sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) } returns gyroSensor
        every { sensorManager.registerListener(any<SensorEventListener>(), any(), any<Int>()) } returns true
        val subject = GyroSensorStream(context, testScope, inputEvaluator)
        assertTrue(subject.start())
        assertTrue(subject.start())
        assertTrue(subject.start())
        verify(exactly = 1) {
            sensorManager.registerListener(any<SensorEventListener>(), any(), any<Int>())
        }
    }

    @Test
    fun stop_unregistersListenerAndClearsIsRunning() {
        every { sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) } returns gyroSensor
        every { sensorManager.registerListener(any<SensorEventListener>(), any(), any<Int>()) } returns true
        val subject = GyroSensorStream(context, testScope, inputEvaluator)
        subject.start()
        subject.stop()
        verify { sensorManager.unregisterListener(subject) }
        assertFalse(subject.isRunning)
    }

    @Test
    fun stop_idempotent_doesNotDoubleUnregister() {
        every { sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) } returns gyroSensor
        every { sensorManager.registerListener(any<SensorEventListener>(), any(), any<Int>()) } returns true
        val subject = GyroSensorStream(context, testScope, inputEvaluator)
        subject.start()
        subject.stop()
        subject.stop()
        subject.stop()
        verify(exactly = 1) { sensorManager.unregisterListener(any<SensorEventListener>()) }
    }

    @Test
    fun stop_withoutPriorStart_isNoOp() {
        every { sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) } returns gyroSensor
        val subject = GyroSensorStream(context, testScope, inputEvaluator)
        subject.stop()
        verify(exactly = 0) { sensorManager.unregisterListener(any<SensorEventListener>()) }
    }

    @Test
    fun registrationFails_returnsFalseAndStreamStaysIdle() {
        every { sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) } returns gyroSensor
        every { sensorManager.registerListener(any<SensorEventListener>(), any(), any<Int>()) } returns false
        val subject = GyroSensorStream(context, testScope, inputEvaluator)
        assertFalse(subject.start())
        assertFalse(subject.isRunning)
    }

    @Test
    fun customSamplingPeriod_isPassedThroughToRegisterListener() {
        every { sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) } returns gyroSensor
        every { sensorManager.registerListener(any<SensorEventListener>(), any(), any<Int>()) } returns true
        val subject = GyroSensorStream(context, testScope, inputEvaluator)
        assertTrue(subject.start(samplingPeriodHint = SensorManager.SENSOR_DELAY_FASTEST))
        verify {
            sensorManager.registerListener(subject, gyroSensor, SensorManager.SENSOR_DELAY_FASTEST)
        }
    }

    @Test
    fun startStopStart_reRegistersAfterStop() {
        every { sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) } returns gyroSensor
        every { sensorManager.registerListener(any<SensorEventListener>(), any(), any<Int>()) } returns true
        val subject = GyroSensorStream(context, testScope, inputEvaluator)
        subject.start()
        subject.stop()
        subject.start()
        verify(exactly = 2) {
            sensorManager.registerListener(any<SensorEventListener>(), any(), any<Int>())
        }
        assertTrue(subject.isRunning)
    }

    @Test
    fun eventsFlow_isExposed_andEmpty_beforeAnySensorActivity() {
        // The SharedFlow's replay buffer is 0, so a fresh subscriber sees no
        // backlog. We don't trigger any sensor callbacks here (would require
        // SensorEvent construction); just verify the property is accessible.
        every { sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) } returns gyroSensor
        val subject = GyroSensorStream(context, testScope, inputEvaluator)
        assertEquals(0, subject.events.replayCache.size)
    }
}
