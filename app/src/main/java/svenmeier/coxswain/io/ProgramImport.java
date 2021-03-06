package svenmeier.coxswain.io;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.util.Log;
import android.util.Pair;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.text.ParseException;
import java.util.List;

import svenmeier.coxswain.Coxswain;
import svenmeier.coxswain.Gym;
import svenmeier.coxswain.R;
import svenmeier.coxswain.garmin.TCX2Workout;
import svenmeier.coxswain.gym.Program;
import svenmeier.coxswain.gym.Snapshot;
import svenmeier.coxswain.gym.Workout;

/**
 */
public class ProgramImport implements Import<Program> {

	private Context context;

	private Handler handler = new Handler();

	private final Gym gym;

	public ProgramImport(Context context) {
		this.context = context;

		this.handler = new Handler();

		this.gym = Gym.instance(context);
	}

	public void start(Uri uri) {
		new Reading(uri);
	}

	private class Reading implements Runnable {

		private final Uri uri;

		public Reading(Uri uri) {
			this.uri = uri;

			new Thread(this).start();
		}

		@Override
		public void run() {
			toast(context.getString(R.string.program_import_starting));

			try {
				write();
			} catch (Exception e) {
				Log.e(Coxswain.TAG, "export failed", e);
				toast(context.getString(R.string.program_import_failed));
				return;
			}

			toast(String.format(context.getString(R.string.program_import_finished)));
		}

		private void write() throws IOException, ParseException {

			Program program;

			Reader reader = new BufferedReader(new InputStreamReader(context.getContentResolver().openInputStream(uri)));
			try {
				program = new Json2Program(reader).program();
			} finally {
				reader.close();
			}

			gym.mergeProgram(program);
		}
	}

	private void toast(final String text) {
		handler.post(new Runnable() {
			@Override
			public void run() {
				Toast.makeText(context, text, Toast.LENGTH_LONG).show();
			}
		});
	}
}
