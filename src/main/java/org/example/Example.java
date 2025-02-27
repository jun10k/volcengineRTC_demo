package org.example;

import com.ss.bytertc.defines.*;
import com.ss.bytertc.engine.RTCEngine;
import com.ss.bytertc.engine.RTCRoom;
import com.ss.bytertc.enums.*;
import com.ss.bytertc.handler.AudioFrameObserver;
import com.ss.bytertc.handler.RTCEngineEventHandler;
import com.ss.bytertc.handler.RTCRoomEventHandler;
import com.ss.bytertc.utils.BuildFrame;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Objects;
import java.util.Scanner;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public class Example {
    private String appId = "";
    private String userId = "";
    private String roomToken = "";
    private String roomId = "";
    private JSONObject engineParameters = null;

    public Example(String configFile) {
        initConfig(configFile);
    }

    public static class RTCEngineEventHandlerImpl extends RTCEngineEventHandler {
        @Override
        public void onExtensionAccessError(String extensionName, String msg) {
            System.out.printf("RTCEngineEventHandlerImpl::onExtensionAccessError, extension_name: %s, msg: %s%n", extensionName, msg);
        }
    }

    public static class RTCRoomEventHandlerImpl extends RTCRoomEventHandler {
        @Override
        public void onRoomStateChangedWithReason(String roomId, String uid, RoomState state, RoomStateChangeReason reason) {
            System.out.printf("RTCRoomEventHandlerImpl::onRoomStateChangedWithReason, room_id: %s, uid: %s, state: %d, reason: %d%n", roomId, uid, state.value(), reason.value());
        }

        @Override
        public void onUserJoined(UserInfo userInfo) {
            System.out.printf("RTCRoomEventHandlerImpl::onUserJoined, user_info: %s%n", userInfo.uid);
        }

        @Override
        public void onUserLeave(String uid, UserOfflineReason reason) {
            System.out.printf("RTCRoomEventHandlerImpl::onUserLeave, uid: %s, reason: %d", uid, reason.value());
        }

        @Override
        public void onNetworkQuality(NetworkQualityStats local_quality, NetworkQualityStats[] remote_qualities) {
            System.out.println("RTCRoomEventHandlerImpl::onNetworkQuality, local_quality: " + local_quality.toString() + ", remote_qualities: " + Arrays.toString(remote_qualities));
        }

        @Override
        public void onRoomStateChanged(String room_id, String uid, int state, String extra_info) {
            System.out.println("RTCRoomEventHandlerImpl::onRoomStateChanged, room_id: " + room_id + ", uid: " + uid + ", state: " + state + ", extra_info: " + extra_info);
        }
    }

    public static class AudioFrameObserverImpl extends AudioFrameObserver {
        public BlockingQueue<AudioFrame> remoteAudioFrameQueue;

        public AudioFrameObserverImpl() {
            remoteAudioFrameQueue = new LinkedBlockingQueue<>(10);
        }

        @Override
        public void onRemoteUserAudioFrame(RemoteStreamKey streamInfo, AudioFrame audioFrame) {
            HashMap<String, String> streamInfoMap = new HashMap<>();
            streamInfoMap.put("roomID", streamInfo.roomId);
            streamInfoMap.put("userID", streamInfo.userId);
            streamInfoMap.put("streamIndex", streamInfo.streamIndex.name());

            HashMap<String, String> audioFrameMap = new HashMap<>();
            audioFrameMap.put("sampleRate", audioFrame.sampleRate().name());
            audioFrameMap.put("channel", audioFrame.channel().name());
            audioFrameMap.put("frameType", audioFrame.frameType().name());
            audioFrameMap.put("isMutedData", Boolean.toString(audioFrame.isMutedData()));

            System.out.println("AudioFrameObserverImpl::onRemoteUserAudioFrame, streamInfoMap:");
            streamInfoMap.forEach((key, value) -> System.out.println("Key: " + key + ", Value: " + value));

            System.out.println("AudioFrameObserverImpl::onRemoteUserAudioFrame, audioFrameMap:");
            audioFrameMap.forEach((key, value) -> System.out.println("Key: " + key + ", Value: " + value));

            if (remoteAudioFrameQueue.remainingCapacity() == 0) {
                System.out.println("audio frame pushing queue is full, drop this frame");
                return;
            }

            int dataSize = audioFrame.dataSize();
            ByteBuffer buffer = audioFrame.data();
            if (buffer == null) {
                System.out.println("***** audioFrame buffer is null, return");
                return ;
            }
            AudioFrameBuilder audioFrameBuilder = new AudioFrameBuilder(
                    audioFrame.sampleRate(),
                    audioFrame.channel(),
                    0, // audioFrame.timestampUs(),
                    audioFrame.data(),
                    audioFrame.dataSize(),
                    true
            );

            AudioFrame audioFrameBak = BuildFrame.buildAudioFrame(audioFrameBuilder);
            try {
                remoteAudioFrameQueue.put(audioFrameBak);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public static class FrameUtil {
        public static ByteBuffer generate10MsAudioFrameData() {
            int sampleRate = 16000;
            int durationMs = 10;
            int frequencyHz = 440;
            int amplitude = 32767;
            int numSamples = (int) (sampleRate * durationMs / 1000);

            ByteBuffer audioData = ByteBuffer.allocateDirect(numSamples);

            for (int i = 0; i < numSamples; i++) {
                float t = (float) i / sampleRate;
                float value = (float) (amplitude * Math.sin(2 * Math.PI * frequencyHz * t));
                audioData.put((byte) (int) (value % 256));
            }

            audioData.flip();
            return audioData; // length = audioData.limit()
        }
    }

    public static class PushMethods {
        public static void pushRemoteAudioFrameBackToRemote(RTCEngine engine, BlockingQueue<AudioFrame> queue, AtomicBoolean isPushing) {
            System.out.println("PushMethods::pushRemoteAudioFrameBackToRemote");
            while (isPushing.get()) {
                try {
                    AudioFrame audioFrame = queue.take();
                    if (audioFrame.getNativeFrame() == null && !isPushing.get()) {
                        break;
                    }
                    // 业务处理逻辑
                    int ret = engine.pushExternalAudioFrame(audioFrame);
                    audioFrame.release();
                    System.out.println("pushExternalAudioFrame ret: " + ret + ", pending queue size: " + queue.size());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        public static void pushFixedFreqAudioFrame(RTCEngine engine, AtomicBoolean isPushing) {
            System.out.println("PushMethods::pushFixedFreqAudioFrame start, isPushing: " + isPushing);
            AudioFrameBuilder audioFrameBuilder = new AudioFrameBuilder(AudioSampleRate.AudioSampleRate16000, AudioChannel.AudioChannelMono);

            ByteBuffer data = FrameUtil.generate10MsAudioFrameData();
            audioFrameBuilder.setData(data);
            audioFrameBuilder.setDataSize(data.limit());

            AudioFrame audioFrame = BuildFrame.buildAudioFrame(audioFrameBuilder);
            while (isPushing.get()) {
                int ret = engine.pushExternalAudioFrame(audioFrame);
                System.out.println("pushExternalAudioFrame ret: " + ret);

                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            System.out.println("PushMethods::pushFixedFreqAudioFrame end, isPushing: " + isPushing);
        }

        public static void pushVideoFrame(RTCEngine engine, AtomicBoolean isPushing) {
            System.out.println("PushMethods::pushVideoFrame");

            int kHeight = 640;
            int kWidth = 360;
            int kFps = 15;

            int eachMonochromeFrameStayTimes = (int)(kFps / 5);
            int width = kWidth;
            int height = kHeight;
            float durationSecs = 1f / kFps;
            int pushTimes = 0;

            while (isPushing.get()) {
                byte[][] buffers = null;
                int bufferCount = 0;
                int temp = pushTimes % (eachMonochromeFrameStayTimes * 3);
                if (temp >= 0 && temp < eachMonochromeFrameStayTimes) {
                    buffers = createGreenI420Frame(width, height);
                } else if (temp >= eachMonochromeFrameStayTimes && temp < 2 * eachMonochromeFrameStayTimes) {
                    buffers = createBlackI420Frame(width, height);
                } else {
                    buffers = createWhiteI420Frame(width, height);
                }
                bufferCount = buffers.length;
                pushTimes++;

                VideoFrameData frame =  new VideoFrameData();
                frame.bufferType = VideoBufferType.VideoBufferTypeRawMemory;
                frame.pixelFormat = VideoPixelFormat.VideoPixelFormatI420;
                frame.contentType = VideoContentType.VideoContentTypeNormalFrame;
                frame.numberOfPlanes = buffers.length;

                ByteBuffer[] byteBuffers = new ByteBuffer[bufferCount]; // 3
                for (int i = 0; i < bufferCount; i++) {
                    byteBuffers[i] = ByteBuffer.allocateDirect(buffers[i].length);
                    byteBuffers[i].put(buffers[i]);
                    byteBuffers[i].flip();
                }
                frame.planeData = byteBuffers;

                frame.planeStride = new int[]{width, width >> 1, width >> 1, 0};

                frame.seiData = ByteBuffer.allocateDirect(3);
                frame.roiData = ByteBuffer.allocateDirect(4);
                for (int j = 0; j < 3; j++) {
                    frame.seiData.put((byte) (1 + j));
                }
                for (int j = 0; j < 4; j++) {
                    frame.roiData.put((byte) (2 + j));
                }
                frame.width = width;
                frame.height = height;
                frame.rotation = VideoRotation.VideoRotation0;

                // 获取当前时间戳
                Instant now = Instant.now();

                // 计算微秒级别的时间戳
                frame.timestampUs = now.getEpochSecond() * 1_000_000 + now.getNano() / 1_000;

                frame.textureId = 0;
                frame.textureMatrix = new float[16];
                int ret = engine.pushExternalVideoFrame(frame);
                System.out.println("pushExternalVideoFrame ret:" + ret + ", timestampUs: " + frame.timestampUs);

                Example.sleepFunc((long) durationSecs);
            }
        }

        public static byte[][] createMonochromeI420Frame(int width, int height, int y, int u, int v) {
            int ySize = width * height;
            int uSize = (width / 2) * (height / 2);
            int vSize = (width / 2) * (height / 2);

            byte[] yPlane = new byte[ySize];
            Arrays.fill(yPlane, (byte) y);

            byte[] uPlane = new byte[uSize];
            Arrays.fill(uPlane, (byte) u);

            byte[] vPlane = new byte[vSize];
            Arrays.fill(vPlane, (byte) v);

            return new byte[][]{yPlane, uPlane, vPlane};
        }

        public static byte[][] createGreenI420Frame(int width, int height) {
            return createMonochromeI420Frame(width, height, 195, 85, 43);
        }

        public static byte[][] createBlackI420Frame(int width, int height) {
            return createMonochromeI420Frame(width, height, 0, 128, 128);
        }

        public static byte[][] createWhiteI420Frame(int width, int height) {
            return createMonochromeI420Frame(width, height,255, 128, 128);
        }
    }

    @Override
    public String toString() {
        return "ExampleTest{" +
                "appId='" + appId + '\'' +
                ", userId='" + userId + '\'' +
                ", roomToken='" + roomToken + '\'' +
                ", roomId='" + roomId + '\'' +
                ", engineParameters=" + engineParameters +
                '}';
    }

    private void initConfig(String configFilePath) {
        File configFile = new File(configFilePath);
        assert configFile.exists() : configFilePath + "文件不存在";
        try {
            JSONObject config = new JSONObject(new JSONTokener(new FileReader(configFilePath)));

            System.out.println("Engine Config: " + config);

            if (appId.isEmpty()) {
                this.appId = config.getString("app_id");
            }
            if (userId.isEmpty()) {
                this.userId = config.getString("user_id");
            }
            if (roomToken.isEmpty()) {
                this.roomToken = config.getString("room_token");
            }
            if (roomId.isEmpty()) {
                this.roomId = config.getString("room_id");
            }
            if (Objects.isNull(engineParameters)) {
                this.engineParameters = config.getJSONObject("engine_parameters");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void sleepFunc(long sleepSecs) {
        if (sleepSecs <= 0) {
            return;
        }
        try {
            Thread.sleep(sleepSecs * 1000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public int run(long timeoutSecs, boolean isPushEchoAudioFrameToRemote) {
        assert !appId.isEmpty() : "appId 禁止为空";
        assert !userId.isEmpty() : "userId 禁止为空";
        assert !roomId.isEmpty() : "roomId 禁止为空";
        assert engineParameters != null : "engineParameters 禁止为空";

        // 创建引擎
        EngineConfig engineConfig = new EngineConfig(appId, engineParameters);
        RTCEngineEventHandler engineConfigHandler = new Example.RTCEngineEventHandlerImpl();
        RTCEngine engine = RTCEngine.createRTCEngine(engineConfig, engineConfigHandler);
        // 获取SDK版本号，需要在创建引擎之后
        String sdkVersion = RTCEngine.getSDKVersion();
        System.out.println("ByteRTC version: " + sdkVersion);

        System.out.println("RTC engine created. engine config: " + engineConfig.toString() + ", handler: " + engineConfigHandler);

        // 创建房间
        RTCRoom room = engine.createRTCRoom(roomId);
        RTCRoomConfig roomConfig = new RTCRoomConfig();

        UserInfo userInfo = new UserInfo(userId, null);
        // 加入房间
        int result = room.joinRoom(roomToken, userInfo, roomConfig);
        System.out.printf("RTC room created. room ID: %s, result: %d%n", roomId, result);
        if (result < 0) {
            return -1;
        }
        RTCRoomEventHandler roomEventHandler = new Example.RTCRoomEventHandlerImpl();
        room.setRTCRoomEventHandler(roomEventHandler);

        Example.AudioFrameObserverImpl audioFrameObserver = new Example.AudioFrameObserverImpl();
        engine.registerAudioFrameObserver(audioFrameObserver);

        // 打开远程用户音频帧回调
        AudioFormat audioFormat = new AudioFormat(AudioSampleRate.AudioSampleRate48000, AudioChannel.AudioChannelStereo, 0);
        engine.enableAudioFrameCallback(AudioFrameCallbackMethod.RemoteUser, audioFormat);

        AtomicBoolean isPushing = new AtomicBoolean(true);
        // 设置外部音频源
        engine.setAudioSourceType(AudioSourceType.AudioSourceTypeExternal);

        Thread externalAudioPusher = null;
        System.out.println("isPushEchoAudioFrameToRemote: " + isPushEchoAudioFrameToRemote);
        // push 音频流
        if (isPushEchoAudioFrameToRemote) {
            externalAudioPusher = new Thread(() -> Example.PushMethods.pushRemoteAudioFrameBackToRemote(engine, audioFrameObserver.remoteAudioFrameQueue, isPushing));
        }
        else {
            externalAudioPusher = new Thread(() -> Example.PushMethods.pushFixedFreqAudioFrame(engine, isPushing));
        }
        externalAudioPusher.start();

        // push 视频流
        engine.setVideoSourceType(StreamIndex.StreamIndexMain, VideoSourceType.VideoSourceTypeExternal);
        Thread externalVideoPusher = new Thread(() -> Example.PushMethods.pushVideoFrame(engine, isPushing));
        externalVideoPusher.start();

        if (timeoutSecs > 0) {
            System.out.println("main Thread sleep " + timeoutSecs + "s...");
            sleepFunc(timeoutSecs);
        } else {
            Scanner scanner = new Scanner(System.in);
            System.out.println("请输入任意字符后按回车键退出程序...");
            scanner.nextLine();
            System.out.println("已接收到输入，程序即将退出。");
            scanner.close();
        }

        isPushing.set(false);

        try {
            audioFrameObserver.remoteAudioFrameQueue.put(new AudioFrame(null));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        try {
            externalAudioPusher.join();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        try {
            externalVideoPusher.join();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        System.out.println("destroy room and engine...");
        room.leaveRoom();
        room.destroy();
        RTCEngine.destroyRTCEngine();

        return 0;
    }
}

