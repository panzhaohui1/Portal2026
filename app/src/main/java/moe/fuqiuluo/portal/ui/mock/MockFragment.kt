package moe.fuqiuluo.portal.ui.mock

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckedTextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tencent.bugly.crashreport.CrashReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.fuqiuluo.portal.R
import moe.fuqiuluo.portal.android.root.ShellUtils
import moe.fuqiuluo.portal.android.widget.RockerView
import moe.fuqiuluo.portal.android.window.OverlayUtils
import moe.fuqiuluo.portal.databinding.FragmentMockBinding
import moe.fuqiuluo.portal.ext.altitude
import moe.fuqiuluo.portal.ext.autoEnableGnssMock
import moe.fuqiuluo.portal.ext.drawOverOtherAppsEnabled
import moe.fuqiuluo.portal.ext.historicalLocations
import moe.fuqiuluo.portal.ext.hookSensor
import moe.fuqiuluo.portal.ext.needOpenSELinux
import moe.fuqiuluo.portal.ext.rawHistoricalLocations
import moe.fuqiuluo.portal.ext.selectLocation
import moe.fuqiuluo.portal.ext.speed
import moe.fuqiuluo.portal.service.MockServiceHelper
import moe.fuqiuluo.portal.ui.viewmodel.MockServiceViewModel
import moe.fuqiuluo.portal.ui.viewmodel.MockViewModel
import moe.fuqiuluo.xposed.utils.FakeLoc

class MockFragment : Fragment() {
    private var _binding: FragmentMockBinding? = null
    private val binding get() = _binding!!

    private val mockViewModel by lazy { ViewModelProvider(this)[MockViewModel::class.java] }
    private val mockServiceViewModel by activityViewModels<MockServiceViewModel>()

    @SuppressLint("SetTextI18n")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMockBinding.inflate(inflater, container, false)

        binding.fabMockLocation.setOnClickListener {
            if (!OverlayUtils.hasOverlayPermissions(requireContext())) {
                Toast.makeText(requireContext(), "请授权悬浮窗权限", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
        }

        binding.fabMockLocation.setOnLongClickListener {
            Toast.makeText(requireContext(), "糸守町", Toast.LENGTH_SHORT).show()
            true
        }

        if (mockServiceViewModel.isServiceStart()) {
            binding.switchMock.text = "停止模拟"
            ContextCompat.getDrawable(requireContext(), R.drawable.rounded_play_disabled_24)?.let {
                binding.switchMock.icon = it
            }
        }

        binding.switchMock.setOnClickListener {
           if (mockServiceViewModel.isServiceStart()) {
                tryCloseService(it as MaterialButton)
            } else {
                tryOpenService(it as MaterialButton)
            }
        }

        with(mockServiceViewModel) {
            if (rocker.isStart) {
                binding.rocker.toggle()
            }
            binding.rocker.setOnClickListener {
                if (locationManager == null) {
                    Toast.makeText(requireContext(), "定位服务加载异常", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (!isServiceStart()) {
                    Toast.makeText(requireContext(), "请先启动模拟", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val checkedTextView = it as CheckedTextView
                checkedTextView.toggle()

                if (!requireContext().drawOverOtherAppsEnabled()) {
                    Toast.makeText(requireContext(), "请授权悬浮窗权限", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                lifecycleScope.launch(Dispatchers.Main) {
                    if (checkedTextView.isChecked) {
                        rocker.show()
                    } else {
                        rocker.hide()
                        rockerCoroutineController.pause()
                    }
                }
            }

            rocker.setRockerListener(object: RockerView.Companion.OnMoveListener {
                override fun onAngle(angle: Double) {
                    MockServiceHelper.setBearing(locationManager!!, angle)
                    FakeLoc.bearing = angle
                    FakeLoc.hasBearings = true
                }

                override fun onLockChanged(isLocked: Boolean) {
                    isRockerLocked = isLocked
                }

                override fun onFinished() {
                    if (!isRockerLocked) {
                        rockerCoroutineController.pause()
                    }
                }

                override fun onStarted() {
                    rockerCoroutineController.resume()
                }
            })
        }

        requireContext().selectLocation?.let {
            binding.mockLocationName.text = it.name
            binding.mockLocationAddress.text = it.address
            binding.mockLocationLatlon.text = it.lat.toString().take(8) + ", " + it.lon.toString().take(8)
            mockServiceViewModel.selectedLocation = it
        }

        val locations = requireContext().historicalLocations

        binding.mockLocationCard.setOnClickListener {
            val location = MockServiceHelper.getLocation(mockServiceViewModel.locationManager!!)
            Toast.makeText(requireContext(), "Location$location, ListenerSize: ${MockServiceHelper.getLocationListenerSize(mockServiceViewModel.locationManager!!)}", Toast.LENGTH_SHORT).show()
        }

        // 2024.10.10: sort historical locations
        val historicalLocationAdapter = HistoricalLocationAdapter(locations.sortedBy { it.name }.toMutableList()) { loc, isLongClick ->
            if (isLongClick) {
                Toast.makeText(requireContext(), "长按", Toast.LENGTH_SHORT).show()
            } else {
                binding.mockLocationName.text = loc.name
                binding.mockLocationAddress.text = loc.address
                binding.mockLocationLatlon.text = loc.lat.toString().take(8) + ", " + loc.lon.toString().take(8)
                mockServiceViewModel.selectedLocation = loc
                requireContext().selectLocation = loc

                if (mockServiceViewModel.locationManager == null) {
                    Toast.makeText(requireContext(), "定位服务加载异常", Toast.LENGTH_SHORT).show()
                    CrashReport.postCatchedException(RuntimeException("运行时mockServiceViewModel.locationManager为空！"))
                    return@HistoricalLocationAdapter
                }

                if (MockServiceHelper.isMockStart(mockServiceViewModel.locationManager!!)) {
                    if (MockServiceHelper.setLocation(mockServiceViewModel.locationManager!!, loc.lat, loc.lon)) {
                        Toast.makeText(requireContext(), "位置更新成功", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(requireContext(), "更新位置失败", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        val recyclerView = binding.historicalLocationList
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = historicalLocationAdapter
        ItemTouchHelper(object: ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                return false
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.adapterPosition
                val location = historicalLocationAdapter[position]
                with(requireContext()) {
                    MaterialAlertDialogBuilder(this)
                        .setTitle("删除位置")
                        .setMessage("确定要删除位置(${location.name})吗？")
                        .setPositiveButton("删除") { _, _ ->
                            historicalLocationAdapter.removeItem(position)
                            rawHistoricalLocations = rawHistoricalLocations.toMutableSet().apply {
                                removeIf { it.split(",")[0] == location.name }
                            }
                            showToast("已删除位置")
                        }
                        .setNegativeButton("取消", { _, _ ->
                            historicalLocationAdapter.notifyItemChanged(position)
                        })
                        .show()
                }
            }
        }).attachToRecyclerView(recyclerView)

        return binding.root
    }

    private fun tryOpenService(button: MaterialButton) {
        if (!OverlayUtils.hasOverlayPermissions(requireContext())) {
            showToast("请授权悬浮窗权限")
            return
        }

        val selectedLocation = mockServiceViewModel.selectedLocation ?: run {
            showToast("请选择一个位置")
            return
        }

        if (mockServiceViewModel.locationManager == null) {
            showToast("定位服务加载异常")
            return
        }

        if (!MockServiceHelper.isServiceInit()) {
            showToast("系统服务注入失败")
            return
        }

        lifecycleScope.launch {
            val context = requireContext()
            val speed = context.speed
            val altitude = context.altitude
            val accuracy = FakeLoc.accuracy

            button.isClickable = false
            try {
                withContext(Dispatchers.IO) {
                    mockServiceViewModel.locationManager!!.let {
                        if (MockServiceHelper.tryOpenMock(it, speed, altitude, accuracy)) {
                            updateMockButtonState(button, "停止模拟", R.drawable.rounded_play_disabled_24)
                        } else {
                            showToast("模拟服务启动失败")
                            return@withContext
                        }

                        // 自动启用GNSS Mock以提供最强防检测效果
                        if (context.autoEnableGnssMock) {
                            try {
                                MockServiceHelper.putConfig(it, context)
                                if (MockServiceHelper.startGnssMock(it)) {
                                    Log.d("MockFragment", "Auto-enabled GNSS Mock successfully")
                                    withContext(Dispatchers.Main) {
                                        showToast("🛡️ 已启用最强防检测（GPS+GNSS+基站）")
                                    }
                                } else {
                                    Log.w("MockFragment", "Failed to auto-enable GNSS Mock")
                                    withContext(Dispatchers.Main) {
                                        showToast("⚠️ GNSS模拟启动失败，仅GPS模拟有效")
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e("MockFragment", "Exception when auto-enabling GNSS Mock", e)
                                withContext(Dispatchers.Main) {
                                    showToast("⚠️ GNSS模拟异常，仅GPS模拟有效")
                                }
                            }
                        }

                        if (!MockServiceHelper.setLocation(it, selectedLocation.lat, selectedLocation.lon)) {
                            showToast("更新位置失败")
                            return@let
                        }

                        if (MockServiceHelper.broadcastLocation(it)) {
                            showToast("更新位置成功")
                        } else {
                            showToast("更新位置失败")
                        }
                    }
                }
            } finally {
                button.isClickable = true
            }
        }


    }

    private fun tryCloseService(button: MaterialButton) {
        if (mockServiceViewModel.locationManager == null) {
            showToast("定位服务加载异常")
            return
        }

        if (!MockServiceHelper.isServiceInit()) {
            showToast("系统服务注入失败")
            return
        }

        lifecycleScope.launch {
            button.isClickable = false
            try {
                val isClosed = withContext(Dispatchers.IO) {
                    if (!MockServiceHelper.isMockStart(mockServiceViewModel.locationManager!!)) {
                        showToast("模拟服务未启动")
                        return@withContext false
                    }

                    // 如果启用了自动GNSS Mock，先停止GNSS Mock
                    if (requireContext().autoEnableGnssMock &&
                        MockServiceHelper.isGnssMockStart(mockServiceViewModel.locationManager!!)) {
                        MockServiceHelper.stopGnssMock(mockServiceViewModel.locationManager!!)
                        Log.d("MockFragment", "Auto-disabled GNSS Mock")
                    }

                    if (MockServiceHelper.tryCloseMock(mockServiceViewModel.locationManager!!)) {
                        updateMockButtonState(button, "开始模拟", R.drawable.rounded_play_arrow_24)
                        return@withContext true
                    } else {
                        showToast("模拟服务停止失败")
                        return@withContext false
                    }
                }
                if (isClosed && mockServiceViewModel.rocker.isStart) {
                    binding.rocker.isClickable = false
                    binding.rocker.toggle()
                    mockServiceViewModel.rocker.hide()
                    mockServiceViewModel.rockerCoroutineController.pause()
                    binding.rocker.isClickable = true
                }
            } finally {
                button.isClickable = true
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun showToast(message: String) = lifecycleScope.launch(Dispatchers.Main) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    private fun updateMockButtonState(button: MaterialButton, text: String, iconRes: Int) = lifecycleScope.launch(Dispatchers.Main) {
        button.text = text
        ContextCompat.getDrawable(requireContext(), iconRes)?.let {
            button.icon = it
        }
    }
} //