package org.hunter.a361camera.camera;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.support.v13.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;

import org.hunter.a361camera.R;
import org.hunter.a361camera.util.ActivityUtils;
import org.hunter.a361camera.view.Camera2Fragment;

public class MainActivity extends AppCompatActivity {

	private static final String[] NEEDED_PERMISSIONS = new String[]{
			Manifest.permission.READ_EXTERNAL_STORAGE,
			Manifest.permission.WRITE_EXTERNAL_STORAGE,
			Manifest.permission.CAMERA,
	};
	private static final int ACTION_REQUEST_PERMISSIONS = 1;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);

		if (ContextCompat.checkSelfPermission(this,NEEDED_PERMISSIONS[0]) != PackageManager.PERMISSION_GRANTED ||
				ContextCompat.checkSelfPermission(this,NEEDED_PERMISSIONS[1]) != PackageManager.PERMISSION_GRANTED ||
				ContextCompat.checkSelfPermission(this,NEEDED_PERMISSIONS[2]) != PackageManager.PERMISSION_GRANTED) {
			ActivityCompat.requestPermissions(this,NEEDED_PERMISSIONS,ACTION_REQUEST_PERMISSIONS);
		}

		Camera2Fragment camera2Fragment = (Camera2Fragment) getFragmentManager()
				.findFragmentById(R.id.contentFrame);
		if (camera2Fragment == null) {
			camera2Fragment = Camera2Fragment.newInstance();
			ActivityUtils.addFragmentToActivity(getFragmentManager(),
					camera2Fragment,R.id.contentFrame);
		}
	}
}
