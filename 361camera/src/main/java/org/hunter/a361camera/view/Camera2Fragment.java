package org.hunter.a361camera.view;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.Fragment;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.DngCreator;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.support.annotation.NonNull;
import android.support.v13.app.FragmentCompat;
import android.support.v4.app.ActivityCompat;
import android.support.v4.view.MotionEventCompat;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import org.hunter.a361camera.R;
import org.hunter.a361camera.util.AutoFocusHelper;
import org.hunter.a361camera.util.LogUtil;
import org.hunter.a361camera.widget.AutoFitTextureView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class Camera2Fragment extends Fragment
		implements View.OnClickListener, FragmentCompat.OnRequestPermissionsResultCallback {
	public static final String CAMERA_FRONT = "1";
	public static final String CAMERA_BACK = "0";
	public static final int TIME_INTERVAL = 1000;
	public static final int IMAGE_SHOW = 100;




	public static final int FOCUS_HIDE = 101;
	/** 从屏幕旋转转换为JPEG方向。 */
	private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
	/** 请求相机权限代码。 */
	private static final int REQUEST_CAMERA_PERMISSIONS = 1;

	/** 拍照需要权限。 */
	private static final String[] CAMERA_PERMISSIONS = {
			Manifest.permission.CAMERA,
			Manifest.permission.READ_EXTERNAL_STORAGE,
			Manifest.permission.WRITE_EXTERNAL_STORAGE,
	};

	/** 预捕获序列超时。 */
	private static final long PRECAPTURE_TIMEOUT_MS = 1000;

	/** 比较纵横比时的公差。 */
	private static final double ASPECT_RATIO_TOLERANCE = 0.005;

	/** Camera2 API保证的最大预览宽度 */
	private static final int MAX_PREVIEW_WIDTH = 1920;

	/** Camera2 API保证的最大预览高度 */
	private static final int MAX_PREVIEW_HEIGHT = 1080;

	private static final String TAG = "Camera2RawFragment";

	/** 摄像头状态：设备已关闭。 */
	private static final int STATE_CLOSED = 0;

	/** 相机状态：设备已打开，但未进行拍摄。 */
	private static final int STATE_OPENED = 1;

	/** 相机状态：显示相机预览。 */
	private static final int STATE_PREVIEW = 2;

	/** 相机状态：在拍摄照片之前等待3A收敛。 */
	private static final int STATE_WAITING_FOR_3A_CONVERGENCE = 3;

	/** 在我们调整焦点后恢复连续焦点模式所需的时间 */
	private static final int DELAY_TIME_RESUME_CONTINUOUS_AF = 1000;

	private static Camera2Fragment INSTANCE;
	private static Camera2Handler mHandler;

	static {
		ORIENTATIONS.append(Surface.ROTATION_0,0);
		ORIENTATIONS.append(Surface.ROTATION_90,90);
		ORIENTATIONS.append(Surface.ROTATION_180,180);
		ORIENTATIONS.append(Surface.ROTATION_270,270);
	}

	/**
	 * 一个计数器，用于跟踪相应的 {@link CaptureRequest} 和 {@link CaptureResult} 跨 {@link CameraCaptureSession} 捕获回调。
	 */
	private final AtomicInteger mRequestCounter = new AtomicInteger();
	/**
	 * {@link Semaphore} 防止应用程序在关闭相机之前退出。
	 */
	private final Semaphore mCameraOpenCloseLock = new Semaphore(1);
	/** 锁定保护摄像头状态。 */
	private final Object mCameraStateLock = new Object();
	/**
	 * 正在进行的JPEG捕获的映射的请求ID。{@link ImageSaver.ImageSaverBuilder}
	 */
	private final TreeMap<Integer,ImageSaver.ImageSaverBuilder> mJpegResultQueue = new TreeMap<>();
	/**
	 * Request ID to {@link ImageSaver.ImageSaverBuilder} mapping for in-progress RAW captures.
	 */
	private final TreeMap<Integer,ImageSaver.ImageSaverBuilder> mRawResultQueue = new TreeMap<>();
	/**
	 * 在UI线程内，这个 {@link Handler} 用于展示 {@link Toast}
	 */
	private final Handler mMessageHandler = new Handler(Looper.getMainLooper()) {
		@Override
		public void handleMessage(Message msg) {
			Activity activity = getActivity();
			if (activity != null) {
				Toast.makeText(activity,(String) msg.obj,Toast.LENGTH_SHORT).show();
			}
		}
	};

	// *********************************************************************************************
	// 受mCameraStateLock保护的状态。
	//
	// 以下状态在UI和后台线程中都使用。名称中带有“Locked”的方法在调用时需要持有mCameraStateLock。
	/**
	 * 用于确定设备何时发生旋转的 {@link OrientationEventListener}。这主要是在设备旋转180度时所必需的
	 * 在这种情况下，不会调用onCreate或onConfigurationChanged，因为视图尺寸保持不变
	 * 但方向发生了变化，因此必须更新预览旋转。
	 */
	private OrientationEventListener mOrientationListener;
	/** {@link AutoFitTextureView} 用于预览相机 */
	private AutoFitTextureView mTextureView;
	private ImageView mImageShow;
	private ImageView mTimer;
	private TextView mTimeText;
	private ImageView mFlashBtn;
	private ImageView mIvFocus;
	private ImageView mIvHdr;
	/** 当前预览的 Surface */
	private Surface mPreviewSurface;
	/** 当前的自动对焦模式，当我们点击对焦时，该模式将切换到自动 */
	private AutoFocusMode mControlAFMode = AutoFocusMode.CONTINUOUS_PICTURE;
	/** 焦点零点区域 */
	private static final MeteringRectangle[] ZERO_WEIGHT_3A_REGION = AutoFocusHelper.getZeroWeightRegion();
	private MeteringRectangle[] mAFRegions = ZERO_WEIGHT_3A_REGION;
	private MeteringRectangle[] mAERegions = ZERO_WEIGHT_3A_REGION;
	/** HDR modre，0表示HDR关闭，而1表示HDR打开。 */
	private int mHdrMode;

	enum AutoFocusMode {
		/** 系统正在持续聚焦。 */
		CONTINUOUS_PICTURE,
		/** 系统正在运行触发扫描。 */
		AUTO;

		int switchToCamera2FocusMode() {
			switch (this) {
				case CONTINUOUS_PICTURE:
					return CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE;
				case AUTO:
					return CameraMetadata.CONTROL_AF_MODE_AUTO;
				default:
					return CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE;
			}
		}
	}

	/** 延时状态，0表示无延时，1表示延时3s，2表示延时10s */
	private short mDelayState = 0; // Timer
	private short mDelayTime;
	/** 闪光模式，0表示关闭，1表示自动，2表示打开 */
	private short mFlashMode = 1;
	/**
	 * An additional thread for running tasks that shouldn't block the UI.  This is used for all
	 * callbacks from the {@link CameraDevice} and {@link CameraCaptureSession}s.
	 */
	private HandlerThread mBackgroundThread;
	/** 前摄还是后摄 {@link CameraDevice}. */
	// private String mCameraId = CAMERA_BACK; // 默认背面摄像头
	private String mCameraId = CAMERA_FRONT; // 默认背面摄像头
	/**
	 * A {@link CameraCaptureSession } for camera preview.
	 */
	private CameraCaptureSession mCaptureSession;
	/**
	 * A reference to the open {@link CameraDevice}.
	 */
	private CameraDevice mCameraDevice;
	/**
	 * The {@link Size} of camera preview.
	 */
	private Size mPreviewSize;
	/**
	 * The {@link CameraCharacteristics} for the currently configured camera device.
	 */
	private CameraCharacteristics mCharacteristics;
	/**
	 * A {@link Handler} for running tasks in the background.
	 */
	private Handler mBackgroundHandler;
	/**
	 * A reference counted holder wrapping the {@link ImageReader} that handles JPEG image
	 * captures. This is used to allow us to clean up the {@link ImageReader} when all background
	 * tasks using its {@link Image}s have completed.
	 */
	private RefCountedAutoCloseable<ImageReader> mJpegImageReader;
	/**
	 * This a callback object for the {@link ImageReader}. "onImageAvailable" will be called when a
	 * JPEG image is ready to be saved.
	 */
	private final ImageReader.OnImageAvailableListener mOnJpegImageAvailableListener
			= new ImageReader.OnImageAvailableListener() {

		@Override
		public void onImageAvailable(ImageReader reader) {
			dequeueAndSaveImage(mJpegResultQueue,mJpegImageReader);
		}

	};
	/**
	 * A reference counted holder wrapping the {@link ImageReader} that handles RAW image captures.
	 * This is used to allow us to clean up the {@link ImageReader} when all background tasks using
	 * its {@link Image}s have completed.
	 */
	private RefCountedAutoCloseable<ImageReader> mRawImageReader;
	/**
	 * This a callback object for the {@link ImageReader}. "onImageAvailable" will be called when a
	 * RAW image is ready to be saved.
	 */
	private final ImageReader.OnImageAvailableListener mOnRawImageAvailableListener
			= new ImageReader.OnImageAvailableListener() {

		@Override
		public void onImageAvailable(ImageReader reader) {
			dequeueAndSaveImage(mRawResultQueue,mRawImageReader);
		}

	};
	/** 当前配置的相机设备是否为固定焦点。 */
	private boolean mNoAFRun = false;

	//**********************************************************************************************
	/** 挂起的用户拍摄照片的请求数。 */
	private int mPendingUserCaptures = 0;
	/**
	 * {@link CaptureRequest.Builder} for the camera preview
	 */
	private CaptureRequest.Builder mPreviewRequestBuilder;
	/**
	 * 摄像机设备的状态。
	 *
	 * @see #mPreCaptureCallback
	 */
	private int mState = STATE_CLOSED;
	/** 与预捕获序列一起使用的计时器，以确保在3A收敛花费太长时间时及时捕获。 */
	private long mCaptureTimer;
	/**
	 * A {@link CameraCaptureSession.CaptureCallback} that handles events for the preview and
	 * pre-capture sequence.
	 */
	private final CameraCaptureSession.CaptureCallback mPreCaptureCallback
			= new CameraCaptureSession.CaptureCallback() {

		private void process(CaptureResult result) {
			synchronized (mCameraStateLock) {
				switch (mState) {
					case STATE_PREVIEW: {
						// We have nothing to do when the camera preview is running normally.
						break;
					}
					case STATE_WAITING_FOR_3A_CONVERGENCE: {
						boolean readyToCapture = true;
						if (!mNoAFRun) {
							Integer afState = result.get(CaptureResult.CONTROL_AF_STATE);
							if (afState == null) {
								break;
							}

							// If auto-focus has reached locked state, we are ready to capture
							readyToCapture =
									(afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
											afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED);
						}

						// If we are running on an non-legacy device, we should also wait until
						// auto-exposure and auto-white-balance have converged as well before
						// taking a picture.
						if (!isLegacyLocked()) {
							Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
							Integer awbState = result.get(CaptureResult.CONTROL_AWB_STATE);
							if (aeState == null || awbState == null) {
								break;
							}

							readyToCapture = readyToCapture &&
									aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED &&
									awbState == CaptureResult.CONTROL_AWB_STATE_CONVERGED;
						}

						// If we haven't finished the pre-capture sequence but have hit our maximum
						// wait timeout, too bad! Begin capture anyway.
						if (!readyToCapture && hitTimeoutLocked()) {
							Log.w(TAG,"Timed out waiting for pre-capture sequence to complete.");
							readyToCapture = true;
						}

						if (readyToCapture && mPendingUserCaptures > 0) {
							// Capture once for each user tap of the "Picture" button.
							while (mPendingUserCaptures > 0) {
								captureStillPictureLocked();
								mPendingUserCaptures--;
							}
							// After this, the camera will go back to the normal state of preview.
							mState = STATE_PREVIEW;
						}
					}
				}
			}
		}

		@Override
		public void onCaptureProgressed(CameraCaptureSession session,CaptureRequest request,
										CaptureResult partialResult) {
			process(partialResult);
		}

		@Override
		public void onCaptureCompleted(CameraCaptureSession session,CaptureRequest request,
									   TotalCaptureResult result) {
			process(result);
		}

	};
	/**
	 * {@link TextureView.SurfaceTextureListener} handles several lifecycle events of a
	 * {@link TextureView}.
	 */
	private final TextureView.SurfaceTextureListener mSurfaceTextureListener
			= new TextureView.SurfaceTextureListener() {

		@Override
		public void onSurfaceTextureAvailable(SurfaceTexture texture,int width,int height) {
			configureTransform(width,height);
		}

		@Override
		public void onSurfaceTextureSizeChanged(SurfaceTexture texture,int width,int height) {
			configureTransform(width,height);
		}

		@Override
		public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
			synchronized (mCameraStateLock) {
				mPreviewSize = null;
			}
			return true;
		}

		@Override
		public void onSurfaceTextureUpdated(SurfaceTexture texture) {
		}

	};
	/**
	 * {@link CameraDevice.StateCallback} is called when the currently active {@link CameraDevice}
	 * changes its state.
	 */
	private final CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {

		@Override
		public void onOpened(CameraDevice cameraDevice) {
			// This method is called when the camera is opened.  We start camera preview here if
			// the TextureView displaying this has been set up.
			synchronized (mCameraStateLock) {
				mState = STATE_OPENED;
				mCameraOpenCloseLock.release();
				mCameraDevice = cameraDevice;

				// Start the preview session if the TextureView has been set up already.
				if (mPreviewSize != null && mTextureView.isAvailable()) {
					createCameraPreviewSessionLocked();
				}
			}
		}

		@Override
		public void onDisconnected(CameraDevice cameraDevice) {
			synchronized (mCameraStateLock) {
				mState = STATE_CLOSED;
				mCameraOpenCloseLock.release();
				cameraDevice.close();
				mCameraDevice = null;
			}
		}

		@Override
		public void onError(CameraDevice cameraDevice,int error) {
			Log.e(TAG,"Received camera device error: " + error);
			synchronized (mCameraStateLock) {
				mState = STATE_CLOSED;
				mCameraOpenCloseLock.release();
				cameraDevice.close();
				mCameraDevice = null;
			}
			Activity activity = getActivity();
			if (null != activity) {
				activity.finish();
			}
		}

	};
	/**
	 * A {@link CameraCaptureSession.CaptureCallback} that handles the still JPEG and RAW capture
	 * request.
	 */
	private final CameraCaptureSession.CaptureCallback mCaptureCallback
			= new CameraCaptureSession.CaptureCallback() {
		@Override
		public void onCaptureStarted(CameraCaptureSession session,CaptureRequest request,
									 long timestamp,long frameNumber) {
			String currentDateTime = generateTimestamp();
			File rawFile = new File(Environment.
					getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
					"RAW_" + currentDateTime + ".dng");
			File jpegFile = new File(Environment.
					getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
					"JPEG_" + currentDateTime + ".jpg");

			// Look up the ImageSaverBuilder for this request and update it with the file name
			// based on the capture start time.
			ImageSaver.ImageSaverBuilder jpegBuilder;
			ImageSaver.ImageSaverBuilder rawBuilder;
			int requestId = (int) request.getTag();
			synchronized (mCameraStateLock) {
				jpegBuilder = mJpegResultQueue.get(requestId);
				rawBuilder = mRawResultQueue.get(requestId);
			}

			if (jpegBuilder != null) {
				jpegBuilder.setFile(jpegFile);
			}
			if (rawBuilder != null) {
				rawBuilder.setFile(rawFile);
			}
		}

		@Override
		public void onCaptureCompleted(CameraCaptureSession session,CaptureRequest request,
									   TotalCaptureResult result) {
			int requestId = (int) request.getTag();
			ImageSaver.ImageSaverBuilder jpegBuilder;
			ImageSaver.ImageSaverBuilder rawBuilder;
			StringBuilder sb = new StringBuilder();

			// Look up the ImageSaverBuilder for this request and update it with the CaptureResult
			synchronized (mCameraStateLock) {
				jpegBuilder = mJpegResultQueue.get(requestId);
				rawBuilder = mRawResultQueue.get(requestId);

				if (jpegBuilder != null) {
					jpegBuilder.setResult(result);
					sb.append("Saving JPEG as: ");
					sb.append(jpegBuilder.getSaveLocation());
				}
				if (rawBuilder != null) {
					rawBuilder.setResult(result);
					if (jpegBuilder != null) {
						sb.append(", ");
					}
					sb.append("Saving RAW as: ");
					sb.append(rawBuilder.getSaveLocation());
				}

				// If we have all the results necessary, save the image to a file in the background.
				handleCompletionLocked(requestId,jpegBuilder,mJpegResultQueue);
				handleCompletionLocked(requestId,rawBuilder,mRawResultQueue);

				finishedCaptureLocked();
			}

			showToast(sb.toString());
		}

		@Override
		public void onCaptureFailed(CameraCaptureSession session,CaptureRequest request,
									CaptureFailure failure) {
			int requestId = (int) request.getTag();
			synchronized (mCameraStateLock) {
				mJpegResultQueue.remove(requestId);
				mRawResultQueue.remove(requestId);
				finishedCaptureLocked();
			}
			showToast("Capture failed!");
		}

	};

	public static Camera2Fragment newInstance() {
		INSTANCE = new Camera2Fragment();
		mHandler = new Camera2Handler(INSTANCE);
		return INSTANCE;
	}

	/**
	 * Given {@code choices} of {@code Size}s supported by a camera, choose the smallest one that
	 * is at least as large as the respective texture view size, and that is at most as large as the
	 * respective max size, and whose aspect ratio matches with the specified value. If such size
	 * doesn't exist, choose the largest one that is at most as large as the respective max size,
	 * and whose aspect ratio matches with the specified value.
	 *
	 * @param choices           The list of sizes that the camera supports for the intended output
	 *                          class
	 * @param textureViewWidth  The width of the texture view relative to sensor coordinate
	 * @param textureViewHeight The height of the texture view relative to sensor coordinate
	 * @param maxWidth          The maximum width that can be chosen
	 * @param maxHeight         The maximum height that can be chosen
	 * @param aspectRatio       The aspect ratio
	 * @return The optimal {@code Size}, or an arbitrary one if none were big enough
	 */
	private static Size chooseOptimalSize(Size[] choices,int textureViewWidth,
										  int textureViewHeight,int maxWidth,int maxHeight,Size aspectRatio) {
		// Collect the supported resolutions that are at least as big as the preview Surface
		List<Size> bigEnough = new ArrayList<>();
		// Collect the supported resolutions that are smaller than the preview Surface
		List<Size> notBigEnough = new ArrayList<>();
		int w = aspectRatio.getWidth();
		int h = aspectRatio.getHeight();
		for (Size option : choices) {
			if (option.getWidth() <= maxWidth && option.getHeight() <= maxHeight &&
					option.getHeight() == option.getWidth() * h / w) {
				if (option.getWidth() >= textureViewWidth &&
						option.getHeight() >= textureViewHeight) {
					bigEnough.add(option);
				} else {
					notBigEnough.add(option);
				}
			}
		}

		// Pick the smallest of those big enough. If there is no one big enough, pick the
		// largest of those not big enough.
		if (bigEnough.size() > 0) {
			return Collections.min(bigEnough,new CompareSizesByArea());
		} else if (notBigEnough.size() > 0) {
			return Collections.max(notBigEnough,new CompareSizesByArea());
		} else {
			Log.e(TAG,"Couldn't find any suitable preview size");
			return choices[0];
		}
	}

	/**
	 * 生成一个字符串，该字符串包含带当前日期和时间的格式化时间戳。
	 *
	 * @return a {@link String} representing a time.
	 */
	private static String generateTimestamp() {
		SimpleDateFormat sdf = new SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS",Locale.US);
		return sdf.format(new Date());
	}

	/**
	 * Cleanup the given {@link OutputStream}.
	 *
	 * @param outputStream the stream to close.
	 */
	private static void closeOutput(OutputStream outputStream) {
		if (null != outputStream) {
			try {
				outputStream.close();
			}
			catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	/**
	 * 如果给定的数组包含给定的整数，则返回true。
	 *
	 * @param modes array to check.
	 * @param mode  integer to get for.
	 * @return true if the array contains the given integer, otherwise false.
	 */
	private static boolean contains(int[] modes,int mode) {
		if (modes == null) {
			return false;
		}
		for (int i : modes) {
			if (i == mode) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Return true if the two given {@link Size}s have the same aspect ratio.
	 *
	 * @param a first {@link Size} to compare.
	 * @param b second {@link Size} to compare.
	 * @return true if the sizes have the same aspect ratio, otherwise false.
	 */
	private static boolean checkAspectsEqual(Size a,Size b) {
		double aAspect = a.getWidth() / (double) a.getHeight();
		double bAspect = b.getWidth() / (double) b.getHeight();
		return Math.abs(aAspect - bAspect) <= ASPECT_RATIO_TOLERANCE;
	}

	/**
	 * 旋转需要从相机传感器的方向转换为设备的当前方向。
	 *
	 * @param c                 the {@link CameraCharacteristics} to query for the camera sensor
	 *                          orientation.
	 * @param deviceOrientation the current device orientation relative to the native device
	 *                          orientation.
	 * @return the total rotation from the sensor orientation to the current device orientation.
	 */
	private static int sensorToDeviceRotation(CameraCharacteristics c,int deviceOrientation) {
		int sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION);

		// Get device orientation in degrees
		deviceOrientation = ORIENTATIONS.get(deviceOrientation);

		// Reverse device orientation for front-facing cameras
		if (c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT) {
			deviceOrientation = -deviceOrientation;
		}

		// Calculate desired JPEG orientation relative to camera orientation to make
		// the image upright relative to the device orientation
		return (sensorOrientation + deviceOrientation + 360) % 360;
	}

	@Override
	public View onCreateView(LayoutInflater inflater,ViewGroup container,
							 Bundle savedInstanceState) {
		return inflater.inflate(R.layout.fragment_camera2,container,false);
	}

	@SuppressLint("ClickableViewAccessibility")
	@Override
	public void onViewCreated(final View view,Bundle savedInstanceState) {
		view.findViewById(R.id.capture).setOnClickListener(this);
		view.findViewById(R.id.switch_camera).setOnClickListener(this);
		mTextureView = (AutoFitTextureView) view.findViewById(R.id.texture_view_camera2);
		mImageShow = (ImageView) view.findViewById(R.id.iv_show_camera2);
		mTimer = (ImageView) view.findViewById(R.id.timer);
		mTimeText = (TextView) view.findViewById(R.id.timer_text);
		mFlashBtn = (ImageView) view.findViewById(R.id.flash);
		mIvFocus = (ImageView) view.findViewById(R.id.iv_focus);
		mIvHdr = (ImageView) view.findViewById(R.id.hdr);
		mTimer.setOnClickListener(this);
		mFlashBtn.setOnClickListener(this);
		mIvHdr.setOnClickListener(this);

		mTextureView.setOnTouchListener((v,event) -> {
			int actionMasked = MotionEventCompat.getActionMasked(event);
			int fingerX, fingerY;
			int length = (int) (getResources().getDisplayMetrics().density * 80);
			if (actionMasked == MotionEvent.ACTION_DOWN) {
				fingerX = (int) event.getX();
				fingerY = (int) event.getY();
				LogUtil.d("onTouch: x->" + fingerX + ",y->" + fingerY);

				mIvFocus.setX(fingerX - length / 2);
				mIvFocus.setY(fingerY - length / 2);

				mIvFocus.setVisibility(View.VISIBLE);
				triggerFocusArea(fingerX,fingerY);
			}

			return false;
		});

		// Setup a new OrientationEventListener.  This is used to handle rotation events like a
		// 180 degree rotation that do not normally trigger a call to onCreate to do view re-layout
		// or otherwise cause the preview TextureView's size to change.
		mOrientationListener = new OrientationEventListener(getActivity(),
				SensorManager.SENSOR_DELAY_NORMAL) {
			@Override
			public void onOrientationChanged(int orientation) {
				if (mTextureView != null && mTextureView.isAvailable()) {
					configureTransform(mTextureView.getWidth(),mTextureView.getHeight());
				}
			}
		};
	}

	@Override
	public void onResume() {
		super.onResume();
		startBackgroundThread();
		openCamera();

		// When the screen is turned off and turned back on, the SurfaceTexture is already
		// available, and "onSurfaceTextureAvailable" will not be called. In that case, we should
		// configure the preview bounds here (otherwise, we wait until the surface is ready in
		// the SurfaceTextureListener).
		if (mTextureView.isAvailable()) {
			configureTransform(mTextureView.getWidth(),mTextureView.getHeight());
		} else {
			mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
		}
		if (mOrientationListener != null && mOrientationListener.canDetectOrientation()) {
			mOrientationListener.enable();
		}
	}

	@Override
	public void onPause() {
		if (mOrientationListener != null) {
			mOrientationListener.disable();
		}
		closeCamera();
		stopBackgroundThread();
		super.onPause();
	}

	@Override
	public void onRequestPermissionsResult(int requestCode,@NonNull String[] permissions,@NonNull int[] grantResults) {
		if (requestCode == REQUEST_CAMERA_PERMISSIONS) {
			for (int result : grantResults) {
				if (result != PackageManager.PERMISSION_GRANTED) {
					showMissingPermissionError();
					return;
				}
			}
		} else {
			super.onRequestPermissionsResult(requestCode,permissions,grantResults);
		}
	}

	private void triggerFocusArea(float x,float y) {
		CameraManager manager = (CameraManager) getActivity().getSystemService(Context.CAMERA_SERVICE);
		try {
			CameraCharacteristics characteristics
					= manager.getCameraCharacteristics(mCameraId);
			Integer sensorOrientation = characteristics.get(
					CameraCharacteristics.SENSOR_ORIENTATION);

			sensorOrientation = sensorOrientation == null ? 0 : sensorOrientation;

			Rect cropRegion = AutoFocusHelper.cropRegionForZoom(characteristics,1f);
			mAERegions = AutoFocusHelper.aeRegionsForNormalizedCoord(x,y,cropRegion,sensorOrientation);
			mAFRegions = AutoFocusHelper.afRegionsForNormalizedCoord(x,y,cropRegion,sensorOrientation);

			// Step 1: Request single frame CONTROL_AF_TRIGGER_START.
			CaptureRequest.Builder builder;
			builder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
			builder.addTarget(mPreviewSurface);
			builder.set(CaptureRequest.CONTROL_MODE,CameraMetadata.CONTROL_MODE_AUTO);

			mControlAFMode = AutoFocusMode.AUTO;

			builder.set(CaptureRequest.CONTROL_AF_MODE,mControlAFMode.switchToCamera2FocusMode());
			builder.set(CaptureRequest.CONTROL_AF_TRIGGER,CaptureRequest.CONTROL_AF_TRIGGER_START);
			mCaptureSession.capture(builder.build(),mPreCaptureCallback,mBackgroundHandler);

			// Step 2: Call repeatingPreview to update mControlAFMode.
			sendRepeatPreviewRequest();
			resumeContinuousAFAfterDelay(DELAY_TIME_RESUME_CONTINUOUS_AF);
		}
		catch (CameraAccessException ex) {
			Log.e(TAG,"Could not execute preview request.",ex);
		}
	}

	private void resumeContinuousAFAfterDelay(int timeMillions) {
		mBackgroundHandler.removeCallbacks(mResumePreviewRunnable);
		mBackgroundHandler.postDelayed(mResumePreviewRunnable,timeMillions);
	}

	/** 可运行以在选项卡聚焦后恢复连续聚焦模式 */
	private final Runnable mResumePreviewRunnable = new Runnable() {
		@Override
		public void run() {
			mAERegions = ZERO_WEIGHT_3A_REGION;
			mAFRegions = ZERO_WEIGHT_3A_REGION;
			mControlAFMode = AutoFocusMode.CONTINUOUS_PICTURE;
			if (mCameraDevice != null) {
				sendRepeatPreviewRequest();
			}
			Message msg = Message.obtain();
			mHandler.sendEmptyMessage(FOCUS_HIDE);
		}
	};

	private boolean sendRepeatPreviewRequest() {
		try {
			CaptureRequest.Builder builder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
			builder.addTarget(mPreviewSurface);
			builder.set(CaptureRequest.CONTROL_MODE,CameraMetadata.CONTROL_MODE_AUTO);
			builder.set(CaptureRequest.CONTROL_AF_MODE,mControlAFMode.switchToCamera2FocusMode());

			mCaptureSession.setRepeatingRequest(builder.build(),mPreCaptureCallback,mBackgroundHandler);
			return true;
		}
		catch (CameraAccessException e) {
			e.printStackTrace();
			return false;
		}
	}

	@Override
	public void onClick(View view) {
		int id = view.getId();
		if (id == R.id.capture) {
			if (mDelayState == 0) {
				takePicture();
			} else {
				new CountDownTimer(mDelayTime,TIME_INTERVAL) {
					@Override
					public void onTick(long millisUntilFinished) {
						mTimeText.setVisibility(View.VISIBLE);
						mTimeText.setText("" + millisUntilFinished / TIME_INTERVAL);
					}

					@Override
					public void onFinish() {
						mTimeText.setVisibility(View.GONE);
						takePicture();
					}
				}.start();
			}
		} else if (id == R.id.switch_camera) {
			switchCamera();
		} else if (id == R.id.timer) {
			switchDelayState();
		} else if (id == R.id.flash) {
			switchFlashMode();
		} else if (id == R.id.hdr) {
			switchHdrMode();
		}
	}

	private void switchHdrMode() {
		switch (mHdrMode) {
			case 0:
				mHdrMode = 1;
				mIvHdr.setImageResource(R.mipmap.hdr_on);
				mPreviewRequestBuilder.set(CaptureRequest.CONTROL_SCENE_MODE,CameraMetadata.CONTROL_SCENE_MODE_HDR);
				break;
			case 1:
				mHdrMode = 0;
				mIvHdr.setImageResource(R.mipmap.hdr_off);
				mPreviewRequestBuilder.set(CaptureRequest.CONTROL_SCENE_MODE,CameraMetadata.CONTROL_SCENE_MODE_DISABLED);
				break;
		}
		try {
			mCaptureSession.setRepeatingRequest(
					mPreviewRequestBuilder.build(),
					mPreCaptureCallback,mBackgroundHandler);
		}
		catch (CameraAccessException e) {
			e.printStackTrace();
		}
	}

	private void setFlashMode() {
		switch (mFlashMode) {
			case 0:
				mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE,CaptureRequest.CONTROL_AE_MODE_ON);
				mPreviewRequestBuilder.set(CaptureRequest.FLASH_MODE,CaptureRequest.FLASH_MODE_OFF);
				break;
			case 1:
				mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE,CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
				break;
			case 2:
				mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE,CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH);
				break;
		}
	}

	private void switchFlashMode() {
		switch (mFlashMode) {
			case 0:
				mFlashMode = 1;
				mFlashBtn.setImageResource(R.mipmap.flash_auto);
				mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE,CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
				try {
					mCaptureSession.setRepeatingRequest(
							mPreviewRequestBuilder.build(),
							mPreCaptureCallback,mBackgroundHandler);
				}
				catch (CameraAccessException e) {
					e.printStackTrace();
					return;
				}

				break;
			case 1:
				mFlashMode = 2;
				mFlashBtn.setImageResource(R.mipmap.flash_on);
				mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE,CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH);
				try {
					mCaptureSession.setRepeatingRequest(
							mPreviewRequestBuilder.build(),
							mPreCaptureCallback,mBackgroundHandler);
				}
				catch (CameraAccessException e) {
					e.printStackTrace();
					return;
				}
				break;
			case 2:
				mFlashMode = 0;
				mFlashBtn.setImageResource(R.mipmap.flash_off);
				mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE,CaptureRequest.CONTROL_AE_MODE_ON);
				mPreviewRequestBuilder.set(CaptureRequest.FLASH_MODE,CaptureRequest.FLASH_MODE_OFF);
				try {
					mCaptureSession.setRepeatingRequest(
							mPreviewRequestBuilder.build(),
							mPreCaptureCallback,mBackgroundHandler);
				}
				catch (CameraAccessException e) {
					e.printStackTrace();
					return;
				}
				break;
		}
	}

	private void switchDelayState() {
		switch (mDelayState) {
			case 0:
				mTimer.setImageResource(R.mipmap.ic_3s);
				mDelayTime = 3 * 1000;
				mDelayState = 1;
				break;
			case 1:
				mTimer.setImageResource(R.mipmap.ic_10s);
				mDelayTime = 10 * 1000;
				mDelayState = 2;
				break;
			case 2:
				mTimer.setImageResource(R.mipmap.timer);
				mDelayTime = 0;
				mDelayState = 0;
				break;
			default:
				break;
		}
	}

	public void switchCamera() {
		if (mCameraId.equals(CAMERA_FRONT)) {
			mCameraId = CAMERA_BACK;
			closeCamera();
			reopenCamera();

		} else if (mCameraId.equals(CAMERA_BACK)) {
			mCameraId = CAMERA_FRONT;
			closeCamera();
			reopenCamera();
		}
	}

	public void reopenCamera() {
		if (mTextureView.isAvailable()) {
			openCamera();
		} else {
			mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
		}
	}

	/**
	 * Sets up state related to camera that is needed before opening a {@link CameraDevice}.
	 */
	private boolean setUpCameraOutputs() {
		Activity activity = getActivity();
		CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
		if (manager == null) {
			ErrorDialog.buildErrorDialog("This device doesn't support Camera2 API.").
					show(getFragmentManager(),"dialog");
			return false;
		}
		try {
			// Find a CameraDevice that supports RAW captures, and configure state.
			for (String cameraId : manager.getCameraIdList()) {
				CameraCharacteristics characteristics
						= manager.getCameraCharacteristics(cameraId);

				if ((!cameraId.equals(CAMERA_FRONT) && (!cameraId.equals(CAMERA_BACK))
						|| (!cameraId.equals(mCameraId)))) {
					continue;
				}
				// We only use a camera that supports RAW in this sample.
				if (!contains(characteristics.get(
								CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES),
						CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)) {
					continue;
				}

				StreamConfigurationMap map = characteristics.get(
						CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

				// For still image captures, we use the largest available size.
				Size largestJpeg = Collections.max(
						Arrays.asList(map.getOutputSizes(ImageFormat.JPEG)),
						new CompareSizesByArea());

				Size largestRaw = Collections.max(
						Arrays.asList(map.getOutputSizes(ImageFormat.RAW_SENSOR)),
						new CompareSizesByArea());

				synchronized (mCameraStateLock) {
					// Set up ImageReaders for JPEG and RAW outputs.  Place these in a reference
					// counted wrapper to ensure they are only closed when all background tasks
					// using them are finished.
					if (mJpegImageReader == null || mJpegImageReader.getAndRetain() == null) {
						mJpegImageReader = new RefCountedAutoCloseable<>(
								ImageReader.newInstance(largestJpeg.getWidth(),
										largestJpeg.getHeight(),ImageFormat.JPEG, /*maxImages*/5));
					}
					mJpegImageReader.get().setOnImageAvailableListener(
							mOnJpegImageAvailableListener,mBackgroundHandler);

					if (mRawImageReader == null || mRawImageReader.getAndRetain() == null) {
						mRawImageReader = new RefCountedAutoCloseable<>(
								ImageReader.newInstance(largestRaw.getWidth(),
										largestRaw.getHeight(),ImageFormat.RAW_SENSOR, /*maxImages*/ 5));
					}
					mRawImageReader.get().setOnImageAvailableListener(
							mOnRawImageAvailableListener,mBackgroundHandler);

					mCharacteristics = characteristics;
				}
				return true;
			}
		}
		catch (CameraAccessException e) {
			e.printStackTrace();
		}

		// If we found no suitable cameras for capturing RAW, warn the user.
		ErrorDialog.buildErrorDialog("This device doesn't support capturing RAW photos").
				show(getFragmentManager(),"dialog");
		return false;
	}

	/** 打开由指定的相机 {@link #mCameraId}. */
	@SuppressWarnings("MissingPermission")
	private void openCamera() {
		if (!setUpCameraOutputs()) {
			return;
		}
		// 	requestCameraPermissions();

		Activity activity = getActivity();
		CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
		try {
			// Wait for any previously running session to finish.
			if (!mCameraOpenCloseLock.tryAcquire(2500,TimeUnit.MILLISECONDS)) {
				throw new RuntimeException("Time out waiting to lock camera opening.");
			}

			String cameraId;
			Handler backgroundHandler;
			synchronized (mCameraStateLock) {
				cameraId = mCameraId;
				backgroundHandler = mBackgroundHandler;
			}

			// Attempt to open the camera. mStateCallback will be called on the background handler's
			// thread when this succeeds or fails.
			manager.openCamera(cameraId,mStateCallback,backgroundHandler);
		}
		catch (CameraAccessException e) {
			e.printStackTrace();
		}
		catch (InterruptedException e) {
			throw new RuntimeException("Interrupted while trying to lock camera opening.",e);
		}
	}

	/** 请求使用相机和保存图片所需的权限。 */
	private void requestCameraPermissions() {
		// if (shouldShowRationale()) {
		// 	PermissionConfirmationDialog.newInstance().show(getChildFragmentManager(),"dialog");
		// } else {
		// 	FragmentCompat.requestPermissions(this,CAMERA_PERMISSIONS,REQUEST_CAMERA_PERMISSIONS);
		// }
	}

	/**
	 * 告诉是否已授予此应用程序所有必要的权限。
	 *
	 * @return True if all the required permissions are granted.
	 */
	private boolean hasAllPermissionsGranted() {
		for (String permission : CAMERA_PERMISSIONS) {
			if (ActivityCompat.checkSelfPermission(getActivity(),permission)
					!= PackageManager.PERMISSION_GRANTED) {
				return false;
			}
		}
		return true;
	}

	/**
	 * 获取是否应显示具有请求权限的基本原理的UI。
	 *
	 * @return True if the UI should be shown.
	 */
	private boolean shouldShowRationale() {
		for (String permission : CAMERA_PERMISSIONS) {
			if (FragmentCompat.shouldShowRequestPermissionRationale(this,permission)) {
				return true;
			}
		}
		return false;
	}

	/** 显示此应用程序确实需要权限并完成该应用程序。 */
	private void showMissingPermissionError() {
		Activity activity = getActivity();
		if (activity != null) {
			Toast.makeText(activity,R.string.request_permission,Toast.LENGTH_SHORT).show();
			activity.finish();
		}
	}

	/**
	 * Closes the current {@link CameraDevice}.
	 */
	private void closeCamera() {
		try {
			mCameraOpenCloseLock.acquire();
			synchronized (mCameraStateLock) {

				// Reset state and clean up resources used by the camera.
				// Note: After calling this, the ImageReaders will be closed after any background
				// tasks saving Images from these readers have been completed.
				mPendingUserCaptures = 0;
				mState = STATE_CLOSED;
				if (null != mCaptureSession) {
					mCaptureSession.close();
					mCaptureSession = null;
				}
				if (null != mCameraDevice) {
					mCameraDevice.close();
					mCameraDevice = null;
				}
				if (null != mJpegImageReader) {
					mJpegImageReader.close();
					mJpegImageReader = null;
				}
				if (null != mRawImageReader) {
					mRawImageReader.close();
					mRawImageReader = null;
				}
			}
		}
		catch (InterruptedException e) {
			throw new RuntimeException("Interrupted while trying to lock camera closing.",e);
		}
		finally {
			mCameraOpenCloseLock.release();
		}
	}

	/**
	 * Starts a background thread and its {@link Handler}.
	 */
	private void startBackgroundThread() {
		mBackgroundThread = new HandlerThread("CameraBackground");
		mBackgroundThread.start();
		synchronized (mCameraStateLock) {
			mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
		}
	}

	/**
	 * Stops the background thread and its {@link Handler}.
	 */
	private void stopBackgroundThread() {
		mBackgroundThread.quitSafely();
		try {
			mBackgroundThread.join();
			mBackgroundThread = null;
			synchronized (mCameraStateLock) {
				mBackgroundHandler = null;
			}
		}
		catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

	// Utility classes and methods:
	// *********************************************************************************************

	/**
	 * Creates a new {@link CameraCaptureSession} for camera preview.
	 * <p/>
	 * Call this only with {@link #mCameraStateLock} held.
	 */
	private void createCameraPreviewSessionLocked() {
		try {
			SurfaceTexture texture = mTextureView.getSurfaceTexture();
			// We configure the size of default buffer to be the size of camera preview we want.
			texture.setDefaultBufferSize(mPreviewSize.getWidth(),mPreviewSize.getHeight());

			// This is the output Surface we need to start preview.
			mPreviewSurface = new Surface(texture);

			// We set up a CaptureRequest.Builder with the output Surface.
			mPreviewRequestBuilder
					= mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
			mPreviewRequestBuilder.addTarget(mPreviewSurface);

			// Here, we create a CameraCaptureSession for camera preview.
			mCameraDevice.createCaptureSession(Arrays.asList(mPreviewSurface,
							mJpegImageReader.get().getSurface(),
							mRawImageReader.get().getSurface()),new CameraCaptureSession.StateCallback() {
						@Override
						public void onConfigured(CameraCaptureSession cameraCaptureSession) {
							synchronized (mCameraStateLock) {
								// The camera is already closed
								if (null == mCameraDevice) {
									return;
								}

								try {
									setup3AControlsLocked(mPreviewRequestBuilder);
									// Default hdr off
									mPreviewRequestBuilder.set(CaptureRequest.CONTROL_SCENE_MODE,CameraMetadata.CONTROL_SCENE_MODE_DISABLED);
									// Finally, we start displaying the camera preview.
									cameraCaptureSession.setRepeatingRequest(
											mPreviewRequestBuilder.build(),
											mPreCaptureCallback,mBackgroundHandler);
									mState = STATE_PREVIEW;
								}
								catch (CameraAccessException | IllegalStateException e) {
									e.printStackTrace();
									return;
								}
								// When the session is ready, we start displaying the preview.
								mCaptureSession = cameraCaptureSession;
							}
						}

						@Override
						public void onConfigureFailed(CameraCaptureSession cameraCaptureSession) {
							showToast("Failed to configure camera.");
						}
					},mBackgroundHandler
			);
		}
		catch (CameraAccessException e) {
			e.printStackTrace();
		}
	}

	/**
	 * Configure the given {@link CaptureRequest.Builder} to use auto-focus, auto-exposure, and
	 * auto-white-balance controls if available.
	 * <p/>
	 * Call this only with {@link #mCameraStateLock} held.
	 *
	 * @param builder the builder to configure.
	 */
	private void setup3AControlsLocked(CaptureRequest.Builder builder) {
		// Enable auto-magical 3A run by camera device
		builder.set(CaptureRequest.CONTROL_MODE,
				CaptureRequest.CONTROL_MODE_AUTO);

		Float minFocusDist =
				mCharacteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);

		// If MINIMUM_FOCUS_DISTANCE is 0, lens is fixed-focus and we need to skip the AF run.
		mNoAFRun = (minFocusDist == null || minFocusDist == 0);

		if (!mNoAFRun) {
			// If there is a "continuous picture" mode available, use it, otherwise default to AUTO.
			if (contains(mCharacteristics.get(
							CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES),
					CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) {
				builder.set(CaptureRequest.CONTROL_AF_MODE,
						CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
			} else {
				builder.set(CaptureRequest.CONTROL_AF_MODE,
						CaptureRequest.CONTROL_AF_MODE_AUTO);
			}
		}

		// If there is an auto-magical flash control mode available, use it, otherwise default to
		// the "on" mode, which is guaranteed to always be available.
		if (contains(mCharacteristics.get(
						CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES),
				CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)) {
			builder.set(CaptureRequest.CONTROL_AE_MODE,
					CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
		} else {
			builder.set(CaptureRequest.CONTROL_AE_MODE,
					CaptureRequest.CONTROL_AE_MODE_ON);
		}

		// If there is an auto-magical white balance control mode available, use it.
		if (contains(mCharacteristics.get(
						CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES),
				CaptureRequest.CONTROL_AWB_MODE_AUTO)) {
			// Allow AWB to run auto-magically if this device supports this
			builder.set(CaptureRequest.CONTROL_AWB_MODE,
					CaptureRequest.CONTROL_AWB_MODE_AUTO);
		}
	}

	/**
	 * Configure the necessary {@link android.graphics.Matrix} transformation to `mTextureView`,
	 * and start/restart the preview capture session if necessary.
	 * <p/>
	 * This method should be called after the camera state has been initialized in
	 * setUpCameraOutputs.
	 *
	 * @param viewWidth  The width of `mTextureView`
	 * @param viewHeight The height of `mTextureView`
	 */
	private void configureTransform(int viewWidth,int viewHeight) {
		Activity activity = getActivity();
		synchronized (mCameraStateLock) {
			if (null == mTextureView || null == activity) {
				return;
			}

			StreamConfigurationMap map = mCharacteristics.get(
					CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

			// For still image captures, we always use the largest available size.
			Size largestJpeg = Collections.max(Arrays.asList(map.getOutputSizes(ImageFormat.JPEG)),
					new CompareSizesByArea());

			// Find the rotation of the device relative to the native device orientation.
			int deviceRotation = activity.getWindowManager().getDefaultDisplay().getRotation();
			Point displaySize = new Point();
			activity.getWindowManager().getDefaultDisplay().getSize(displaySize);

			// Find the rotation of the device relative to the camera sensor's orientation.
			int totalRotation = sensorToDeviceRotation(mCharacteristics,deviceRotation);

			// Swap the view dimensions for calculation as needed if they are rotated relative to
			// the sensor.
			boolean swappedDimensions = totalRotation == 90 || totalRotation == 270;
			int rotatedViewWidth = viewWidth;
			int rotatedViewHeight = viewHeight;
			int maxPreviewWidth = displaySize.x;
			int maxPreviewHeight = displaySize.y;

			if (swappedDimensions) {
				rotatedViewWidth = viewHeight;
				rotatedViewHeight = viewWidth;
				maxPreviewWidth = displaySize.y;
				maxPreviewHeight = displaySize.x;
			}

			// Preview should not be larger than display size and 1080p.
			if (maxPreviewWidth > MAX_PREVIEW_WIDTH) {
				maxPreviewWidth = MAX_PREVIEW_WIDTH;
			}

			if (maxPreviewHeight > MAX_PREVIEW_HEIGHT) {
				maxPreviewHeight = MAX_PREVIEW_HEIGHT;
			}

			// Find the best preview size for these view dimensions and configured JPEG size.
			Size previewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class),
					rotatedViewWidth,rotatedViewHeight,maxPreviewWidth,maxPreviewHeight,
					largestJpeg);

			if (swappedDimensions) {
				mTextureView.setAspectRatio(
						previewSize.getHeight(),previewSize.getWidth());
			} else {
				mTextureView.setAspectRatio(
						previewSize.getWidth(),previewSize.getHeight());
			}

			// Find rotation of device in degrees (reverse device orientation for front-facing
			// cameras).
			int rotation = (mCharacteristics.get(CameraCharacteristics.LENS_FACING) ==
					CameraCharacteristics.LENS_FACING_FRONT) ?
					(360 + ORIENTATIONS.get(deviceRotation)) % 360 :
					(360 - ORIENTATIONS.get(deviceRotation)) % 360;

			Matrix matrix = new Matrix();
			RectF viewRect = new RectF(0,0,viewWidth,viewHeight);
			RectF bufferRect = new RectF(0,0,previewSize.getHeight(),previewSize.getWidth());
			float centerX = viewRect.centerX();
			float centerY = viewRect.centerY();

			// Initially, output stream images from the Camera2 API will be rotated to the native
			// device orientation from the sensor's orientation, and the TextureView will default to
			// scaling these buffers to fill it's view bounds.  If the aspect ratios and relative
			// orientations are correct, this is fine.
			//
			// However, if the device orientation has been rotated relative to its native
			// orientation so that the TextureView's dimensions are swapped relative to the
			// native device orientation, we must do the following to ensure the output stream
			// images are not incorrectly scaled by the TextureView:
			//   - Undo the scale-to-fill from the output buffer's dimensions (i.e. its dimensions
			//     in the native device orientation) to the TextureView's dimension.
			//   - Apply a scale-to-fill from the output buffer's rotated dimensions
			//     (i.e. its dimensions in the current device orientation) to the TextureView's
			//     dimensions.
			//   - Apply the rotation from the native device orientation to the current device
			//     rotation.
			if (Surface.ROTATION_90 == deviceRotation || Surface.ROTATION_270 == deviceRotation) {
				bufferRect.offset(centerX - bufferRect.centerX(),centerY - bufferRect.centerY());
				matrix.setRectToRect(viewRect,bufferRect,Matrix.ScaleToFit.FILL);
				float scale = Math.max(
						(float) viewHeight / previewSize.getHeight(),
						(float) viewWidth / previewSize.getWidth());
				matrix.postScale(scale,scale,centerX,centerY);

			}
			matrix.postRotate(rotation,centerX,centerY);

			mTextureView.setTransform(matrix);

			// Start or restart the active capture session if the preview was initialized or
			// if its aspect ratio changed significantly.
			if (mPreviewSize == null || !checkAspectsEqual(previewSize,mPreviewSize)) {
				mPreviewSize = previewSize;
				if (mState != STATE_CLOSED) {
					createCameraPreviewSessionLocked();
				}
			}
		}
	}

	/**
	 * 启动静态图像捕获。
	 * <p/>
	 * 此功能发送一个捕获请求，在我们的状态机中启动一个预捕获序列，等待自动对焦完成，
	 * 以镜头不再移动的“锁定”状态结束，等待自动曝光选择一个好的曝光值，并等待自动白平衡收敛。
	 */
	private void takePicture() {
		synchronized (mCameraStateLock) {
			mPendingUserCaptures++;

			// If we already triggered a pre-capture sequence, or are in a state where we cannot
			// do this, return immediately.
			if (mState != STATE_PREVIEW) {
				return;
			}

			try {
				// Trigger an auto-focus run if camera is capable. If the camera is already focused,
				// this should do nothing.
				if (!mNoAFRun) {
					mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
							CameraMetadata.CONTROL_AF_TRIGGER_START);
				}

				// If this is not a legacy device, we can also trigger an auto-exposure metering
				// run.
				if (!isLegacyLocked()) {
					// Tell the camera to lock focus.
					mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
							CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START);
				}

				// Update state machine to wait for auto-focus, auto-exposure, and
				// auto-white-balance (aka. "3A") to converge.
				mState = STATE_WAITING_FOR_3A_CONVERGENCE;

				// Start a timer for the pre-capture sequence.
				startTimerLocked();

				// Set flash mode
				setFlashMode();
				// Replace the existing repeating request with one with updated 3A triggers.
				mCaptureSession.capture(mPreviewRequestBuilder.build(),mPreCaptureCallback,
						mBackgroundHandler);
			}
			catch (CameraAccessException e) {
				e.printStackTrace();
			}
		}
	}

	/**
	 * 向相机设备发送捕获请求，以启动针对JPEG和RAW输出的捕获。
	 * <p/>
	 * Call this only with {@link #mCameraStateLock} held.
	 */
	private void captureStillPictureLocked() {
		try {
			final Activity activity = getActivity();
			if (null == activity || null == mCameraDevice) {
				return;
			}
			// This is the CaptureRequest.Builder that we use to take a picture.
			final CaptureRequest.Builder captureBuilder =
					mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);

			captureBuilder.addTarget(mJpegImageReader.get().getSurface());
			captureBuilder.addTarget(mRawImageReader.get().getSurface());

			// Use the same AE and AF modes as the preview.
			setup3AControlsLocked(captureBuilder);

			// Set orientation.
			int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
			captureBuilder.set(CaptureRequest.JPEG_ORIENTATION,
					sensorToDeviceRotation(mCharacteristics,rotation));

			// Set request tag to easily track results in callbacks.
			captureBuilder.setTag(mRequestCounter.getAndIncrement());

			CaptureRequest request = captureBuilder.build();

			// Create an ImageSaverBuilder in which to collect results, and add it to the queue
			// of active requests.
			ImageSaver.ImageSaverBuilder jpegBuilder = new ImageSaver.ImageSaverBuilder(activity)
					.setCharacteristics(mCharacteristics);
			ImageSaver.ImageSaverBuilder rawBuilder = new ImageSaver.ImageSaverBuilder(activity)
					.setCharacteristics(mCharacteristics);

			mJpegResultQueue.put((int) request.getTag(),jpegBuilder);
			mRawResultQueue.put((int) request.getTag(),rawBuilder);

			mCaptureSession.capture(request,mCaptureCallback,mBackgroundHandler);

		}
		catch (CameraAccessException e) {
			e.printStackTrace();
		}
	}

	/**
	 * 在RAWJPEG捕获完成后调用；重置预捕获序列的AF触发状态。
	 * <p/>
	 * Call this only with {@link #mCameraStateLock} held.
	 */
	private void finishedCaptureLocked() {
		try {
			// Reset the auto-focus trigger in case AF didn't run quickly enough.
			if (!mNoAFRun) {
				mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
						CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);

				mCaptureSession.capture(mPreviewRequestBuilder.build(),mPreCaptureCallback,
						mBackgroundHandler);

				mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
						CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
			}
		}
		catch (CameraAccessException e) {
			e.printStackTrace();
		}
	}

	/**
	 * Retrieve the next {@link Image} from a reference counted {@link ImageReader}, retaining
	 * that {@link ImageReader} until that {@link Image} is no longer in use, and set this
	 * {@link Image} as the result for the next request in the queue of pending requests.  If
	 * all necessary information is available, begin saving the image to a file in a background
	 * thread.
	 *
	 * @param pendingQueue the currently active requests.
	 * @param reader       a reference counted wrapper containing an {@link ImageReader} from which
	 *                     to acquire an image.
	 */
	private void dequeueAndSaveImage(TreeMap<Integer,ImageSaver.ImageSaverBuilder> pendingQueue,
									 RefCountedAutoCloseable<ImageReader> reader) {
		synchronized (mCameraStateLock) {
			Map.Entry<Integer,ImageSaver.ImageSaverBuilder> entry =
					pendingQueue.firstEntry();
			ImageSaver.ImageSaverBuilder builder = entry.getValue();

			// Increment reference count to prevent ImageReader from being closed while we
			// are saving its Images in a background thread (otherwise their resources may
			// be freed while we are writing to a file).
			if (reader == null || reader.getAndRetain() == null) {
				Log.e(TAG,"Paused the activity before we could save the image," +
						" ImageReader already closed.");
				pendingQueue.remove(entry.getKey());
				return;
			}

			Image image;
			try {
				image = reader.get().acquireNextImage();
			}
			catch (IllegalStateException e) {
				Log.e(TAG,"Too many images queued for saving, dropping image for request: " +
						entry.getKey());
				pendingQueue.remove(entry.getKey());
				return;
			}

			builder.setRefCountedReader(reader).setImage(image);

			handleCompletionLocked(entry.getKey(),builder,pendingQueue);
		}
	}

	/**
	 * Shows a {@link Toast} on the UI thread.
	 *
	 * @param text The message to show.
	 */
	private void showToast(String text) {
		// We show a Toast by sending request message to mMessageHandler. This makes sure that the
		// Toast is shown on the UI thread.
		Message message = Message.obtain();
		message.obj = text;
		mMessageHandler.sendMessage(message);
	}

	/**
	 * If the given request has been completed, remove it from the queue of active requests and
	 * send an {@link ImageSaver} with the results from this request to a background thread to
	 * save a file.
	 * <p/>
	 * Call this only with {@link #mCameraStateLock} held.
	 *
	 * @param requestId the ID of the {@link CaptureRequest} to handle.
	 * @param builder   the {@link ImageSaver.ImageSaverBuilder} for this request.
	 * @param queue     the queue to remove this request from, if completed.
	 */
	private void handleCompletionLocked(int requestId,ImageSaver.ImageSaverBuilder builder,
										TreeMap<Integer,ImageSaver.ImageSaverBuilder> queue) {
		if (builder == null) {
			return;
		}
		ImageSaver saver = builder.buildIfComplete();
		if (saver != null) {
			queue.remove(requestId);
			AsyncTask.THREAD_POOL_EXECUTOR.execute(saver);
		}
	}

	/**
	 * 检查我们使用的设备是否仅支持LEGACY硬件级别。
	 * <p/>
	 * Call this only with {@link #mCameraStateLock} held.
	 *
	 * @return true if this is a legacy device.
	 */
	private boolean isLegacyLocked() {
		return mCharacteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) ==
				CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY;
	}

	/**
	 * 启动预捕获序列的计时器。
	 * <p/>
	 * Call this only with {@link #mCameraStateLock} held.
	 */
	private void startTimerLocked() {
		mCaptureTimer = SystemClock.elapsedRealtime();
	}

	/**
	 * 检查预捕获序列的计时器是否已命中。
	 * <p/>
	 * Call this only with {@link #mCameraStateLock} held.
	 *
	 * @return true if the timeout occurred.
	 */
	private boolean hitTimeoutLocked() {
		return (SystemClock.elapsedRealtime() - mCaptureTimer) > PRECAPTURE_TIMEOUT_MS;
	}

	private void showIamge(Bitmap bitmap) {
		mImageShow.setImageBitmap(bitmap);
	}

	/**
	 * Runnable that saves an {@link Image} into the specified {@link File}, and updates
	 * {@link android.provider.MediaStore} to include the resulting file.
	 * <p/>
	 * This can be constructed through an {@link ImageSaverBuilder} as the necessary image and
	 * result information becomes available.
	 */
	private static class ImageSaver implements Runnable {

		/**
		 * The image to save.
		 */
		private final Image mImage;
		/**
		 * The file we save the image into.
		 */
		private final File mFile;

		/**
		 * The CaptureResult for this image capture.
		 */
		private final CaptureResult mCaptureResult;

		/**
		 * The CameraCharacteristics for this camera device.
		 */
		private final CameraCharacteristics mCharacteristics;

		/**
		 * The Context to use when updating MediaStore with the saved images.
		 */
		private final Context mContext;

		/**
		 * A reference counted wrapper for the ImageReader that owns the given image.
		 */
		private final RefCountedAutoCloseable<ImageReader> mReader;

		private ImageSaver(Image image,File file,CaptureResult result,
						   CameraCharacteristics characteristics,Context context,
						   RefCountedAutoCloseable<ImageReader> reader) {
			mImage = image;
			mFile = file;
			mCaptureResult = result;
			mCharacteristics = characteristics;
			mContext = context;
			mReader = reader;
		}

		@Override
		public void run() {
			boolean success = false;
			int format = mImage.getFormat();
			switch (format) {
				case ImageFormat.JPEG: {
					ByteBuffer buffer = mImage.getPlanes()[0].getBuffer();
					byte[] bytes = new byte[buffer.remaining()];
					buffer.get(bytes);
					final Bitmap bitmap = BitmapFactory.decodeByteArray(bytes,0,bytes.length);
					Message msg = Message.obtain();
					msg.what = IMAGE_SHOW;
					msg.obj = bitmap;
					mHandler.sendMessage(msg);
					FileOutputStream output = null;
					try {
						output = new FileOutputStream(mFile);
						output.write(bytes);
						success = true;
					}
					catch (IOException e) {
						e.printStackTrace();
					}
					finally {
						mImage.close();
						closeOutput(output);
					}
					break;
				}
				case ImageFormat.RAW_SENSOR: {
					DngCreator dngCreator = new DngCreator(mCharacteristics,mCaptureResult);
					FileOutputStream output = null;
					try {
						output = new FileOutputStream(mFile);
						dngCreator.writeImage(output,mImage);
						success = true;
					}
					catch (IOException e) {
						e.printStackTrace();
					}
					finally {
						mImage.close();
						closeOutput(output);
					}
					break;
				}
				default: {
					Log.e(TAG,"Cannot save image, unexpected image format:" + format);
					break;
				}
			}

			// Decrement reference count to allow ImageReader to be closed to free up resources.
			mReader.close();

			// If saving the file succeeded, update MediaStore.
			if (success) {
				MediaScannerConnection.scanFile(mContext,new String[]{mFile.getPath()},
						/*mimeTypes*/null,new MediaScannerConnection.MediaScannerConnectionClient() {
							@Override
							public void onMediaScannerConnected() {
								// Do nothing
							}

							@Override
							public void onScanCompleted(String path,Uri uri) {
								Log.i(TAG,"Scanned " + path + ":");
								Log.i(TAG,"-> uri=" + uri);
							}
						});
			}
		}

		/**
		 * Builder class for constructing {@link ImageSaver}s.
		 * <p/>
		 * This class is thread safe.
		 */
		public static class ImageSaverBuilder {
			private Image mImage;
			private File mFile;
			private CaptureResult mCaptureResult;
			private CameraCharacteristics mCharacteristics;
			private final Context mContext;
			private RefCountedAutoCloseable<ImageReader> mReader;

			/**
			 * Construct a new ImageSaverBuilder using the given {@link Context}.
			 *
			 * @param context a {@link Context} to for accessing the
			 *                {@link android.provider.MediaStore}.
			 */
			public ImageSaverBuilder(final Context context) {
				mContext = context;
			}

			public synchronized ImageSaverBuilder setRefCountedReader(
					RefCountedAutoCloseable<ImageReader> reader) {
				if (reader == null) {
					throw new NullPointerException();
				}

				mReader = reader;
				return this;
			}

			public synchronized ImageSaverBuilder setImage(final Image image) {
				if (image == null) {
					throw new NullPointerException();
				}
				mImage = image;
				return this;
			}

			public synchronized ImageSaverBuilder setFile(final File file) {
				if (file == null) {
					throw new NullPointerException();
				}
				mFile = file;
				return this;
			}

			public synchronized ImageSaverBuilder setResult(final CaptureResult result) {
				if (result == null) {
					throw new NullPointerException();
				}
				mCaptureResult = result;
				return this;
			}

			public synchronized ImageSaverBuilder setCharacteristics(
					final CameraCharacteristics characteristics) {
				if (characteristics == null) {
					throw new NullPointerException();
				}
				mCharacteristics = characteristics;
				return this;
			}

			public synchronized ImageSaver buildIfComplete() {
				if (!isComplete()) {
					return null;
				}
				return new ImageSaver(mImage,mFile,mCaptureResult,mCharacteristics,mContext,
						mReader);
			}

			public synchronized String getSaveLocation() {
				return (mFile == null) ? "Unknown" : mFile.toString();
			}

			private boolean isComplete() {
				return mImage != null && mFile != null && mCaptureResult != null
						&& mCharacteristics != null;
			}
		}
	}

	/**
	 * Comparator based on area of the given {@link Size} objects.
	 */
	static class CompareSizesByArea implements Comparator<Size> {

		@Override
		public int compare(Size lhs,Size rhs) {
			// We cast here to ensure the multiplications won't overflow
			return Long.signum((long) lhs.getWidth() * lhs.getHeight() -
					(long) rhs.getWidth() * rhs.getHeight());
		}

	}

	/**
	 * A dialog fragment for displaying non-recoverable errors; this {@link Activity} will be
	 * finished once the dialog has been acknowledged by the user.
	 */
	public static class ErrorDialog extends DialogFragment {

		private String mErrorMessage;

		public ErrorDialog() {
			mErrorMessage = "Unknown error occurred!";
		}

		// Build a dialog with a custom message (Fragments require default constructor).
		public static ErrorDialog buildErrorDialog(String errorMessage) {
			ErrorDialog dialog = new ErrorDialog();
			dialog.mErrorMessage = errorMessage;
			return dialog;
		}

		@Override
		public Dialog onCreateDialog(Bundle savedInstanceState) {
			final Activity activity = getActivity();
			return new AlertDialog.Builder(activity)
					.setMessage(mErrorMessage)
					.setPositiveButton(android.R.string.ok,new DialogInterface.OnClickListener() {
						@Override
						public void onClick(DialogInterface dialogInterface,int i) {
							activity.finish();
						}
					})
					.create();
		}
	}

	/**
	 * A wrapper for an {@link AutoCloseable} object that implements reference counting to allow
	 * for resource management.
	 */
	public static class RefCountedAutoCloseable<T extends AutoCloseable> implements AutoCloseable {
		private T mObject;
		private long mRefCount = 0;

		/**
		 * Wrap the given object.
		 *
		 * @param object an object to wrap.
		 */
		public RefCountedAutoCloseable(T object) {
			if (object == null) {
				throw new NullPointerException();
			}
			mObject = object;
		}

		/**
		 * Increment the reference count and return the wrapped object.
		 *
		 * @return the wrapped object, or null if the object has been released.
		 */
		public synchronized T getAndRetain() {
			if (mRefCount < 0) {
				return null;
			}
			mRefCount++;
			return mObject;
		}

		/**
		 * Return the wrapped object.
		 *
		 * @return the wrapped object, or null if the object has been released.
		 */
		public synchronized T get() {
			return mObject;
		}

		/**
		 * Decrement the reference count and release the wrapped object if there are no other
		 * users retaining this object.
		 */
		@Override
		public synchronized void close() {
			if (mRefCount >= 0) {
				mRefCount--;
				if (mRefCount < 0) {
					try {
						mObject.close();
					}
					catch (Exception e) {
						throw new RuntimeException(e);
					}
					finally {
						mObject = null;
					}
				}
			}
		}
	}

	/** 说明必要权限的对话框。 */
	public static class PermissionConfirmationDialog extends DialogFragment {

		public static PermissionConfirmationDialog newInstance() {
			return new PermissionConfirmationDialog();
		}

		@Override
		public Dialog onCreateDialog(Bundle savedInstanceState) {
			final Fragment parent = getParentFragment();
			return new AlertDialog.Builder(getActivity())
					.setMessage(R.string.request_permission)
					.setPositiveButton(android.R.string.ok,new DialogInterface.OnClickListener() {
						@Override
						public void onClick(DialogInterface dialog,int which) {
							FragmentCompat.requestPermissions(parent,CAMERA_PERMISSIONS,
									REQUEST_CAMERA_PERMISSIONS);
						}
					})
					.setNegativeButton(android.R.string.cancel,
							new DialogInterface.OnClickListener() {
								@Override
								public void onClick(DialogInterface dialog,int which) {
									getActivity().finish();
								}
							})
					.create();
		}

	}

	private static class Camera2Handler extends Handler {
		private final WeakReference<Camera2Fragment> fragment;

		public Camera2Handler(Camera2Fragment fragment) {
			this.fragment = new WeakReference<>(fragment);
		}

		@Override
		public void handleMessage(Message msg) {
			switch (msg.what) {
				case IMAGE_SHOW:
					fragment.get().showIamge((Bitmap) msg.obj);
					break;
				case FOCUS_HIDE:
					fragment.get().mIvFocus.setVisibility(View.INVISIBLE);
					break;
				default:
					break;
			}
		}
	}
}

