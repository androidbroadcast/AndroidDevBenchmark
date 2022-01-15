package org.thoughtcrime.securesms.mediasend.v2.capture

import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.LiveData
import app.cash.exhaustive.Exhaustive
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.mediasend.CameraFragment
import org.thoughtcrime.securesms.mediasend.Media
import org.thoughtcrime.securesms.mediasend.v2.MediaSelectionNavigator
import org.thoughtcrime.securesms.mediasend.v2.MediaSelectionNavigator.Companion.requestPermissionsForGallery
import org.thoughtcrime.securesms.mediasend.v2.MediaSelectionViewModel
import org.thoughtcrime.securesms.mms.MediaConstraints
import org.thoughtcrime.securesms.permissions.Permissions
import org.whispersystems.libsignal.util.guava.Optional
import java.io.FileDescriptor

private val TAG = Log.tag(MediaCaptureFragment::class.java)

/**
 * Fragment which displays the proper camera fragment.
 */
class MediaCaptureFragment : Fragment(R.layout.fragment_container), CameraFragment.Controller {

  private val sharedViewModel: MediaSelectionViewModel by viewModels(
    ownerProducer = { requireActivity() }
  )

  private val viewModel: MediaCaptureViewModel by viewModels(
    factoryProducer = { MediaCaptureViewModel.Factory(MediaCaptureRepository(requireContext())) }
  )

  private lateinit var captureChildFragment: CameraFragment
  private lateinit var navigator: MediaSelectionNavigator

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    captureChildFragment = CameraFragment.newInstance() as CameraFragment

    navigator = MediaSelectionNavigator(
      toGallery = R.id.action_mediaCaptureFragment_to_mediaGalleryFragment
    )

    childFragmentManager
      .beginTransaction()
      .replace(R.id.fragment_container, captureChildFragment as Fragment)
      .commitNowAllowingStateLoss()

    viewModel.events.observe(viewLifecycleOwner) { event ->
      @Exhaustive
      when (event) {
        MediaCaptureEvent.MediaCaptureRenderFailed -> {
          Log.w(TAG, "Failed to render captured media.")
          Toast.makeText(requireContext(), R.string.MediaSendActivity_camera_unavailable, Toast.LENGTH_SHORT).show()
        }
        is MediaCaptureEvent.MediaCaptureRendered -> {
          captureChildFragment.fadeOutControls {
            if (isFirst()) {
              sharedViewModel.addCameraFirstCapture(event.media)
            } else {
              sharedViewModel.addMedia(event.media)
            }

            navigator.goToReview(view)
          }
        }
      }
    }

    sharedViewModel.state.observe(viewLifecycleOwner) { state ->
      captureChildFragment.presentHud(state.selectedMedia.size)
    }

    if (isFirst()) {
      requireActivity().onBackPressedDispatcher.addCallback(
        viewLifecycleOwner,
        object : OnBackPressedCallback(true) {
          override fun handleOnBackPressed() {
            requireActivity().finish()
          }
        }
      )
    }
  }

  override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
    Permissions.onRequestPermissionsResult(this, requestCode, permissions, grantResults)
  }

  override fun onResume() {
    super.onResume()
    captureChildFragment.fadeInControls()
  }

  override fun onCameraError() {
    Log.w(TAG, "Camera Error.")

    val context = this.context
    if (context != null) {
      Toast.makeText(context, R.string.MediaSendActivity_camera_unavailable, Toast.LENGTH_SHORT).show()
    } else {
      Log.w(TAG, "Could not post toast, fragment not attached to a context.")
    }
  }

  override fun onImageCaptured(data: ByteArray, width: Int, height: Int) {
    viewModel.onImageCaptured(data, width, height)
  }

  override fun onVideoCaptured(fd: FileDescriptor) {
    viewModel.onVideoCaptured(fd)
  }

  override fun onVideoCaptureError() {
    Log.w(TAG, "Video capture error.")
    Toast.makeText(requireContext(), R.string.MediaSendActivity_camera_unavailable, Toast.LENGTH_SHORT).show()
  }

  override fun onGalleryClicked() {
    requestPermissionsForGallery {
      captureChildFragment.fadeOutControls {
        navigator.goToGallery(requireView())
      }
    }
  }

  override fun getDisplayRotation(): Int {
    return if (Build.VERSION.SDK_INT >= 30) {
      requireContext().display?.rotation ?: 0
    } else {
      @Suppress("DEPRECATION")
      requireActivity().windowManager.defaultDisplay.rotation
    }
  }

  override fun onCameraCountButtonClicked() {
    captureChildFragment.fadeOutControls {
      navigator.goToReview(requireView())
    }
  }

  override fun getMostRecentMediaItem(): LiveData<Optional<Media>> {
    return viewModel.getMostRecentMedia()
  }

  override fun getMediaConstraints(): MediaConstraints {
    return sharedViewModel.getMediaConstraints()
  }

  private fun isFirst(): Boolean {
    return arguments?.getBoolean("first") == true
  }

  companion object {
    const val CAPTURE_RESULT = "capture_result"
    const val CAPTURE_RESULT_OK = "capture_result_ok"
  }
}
