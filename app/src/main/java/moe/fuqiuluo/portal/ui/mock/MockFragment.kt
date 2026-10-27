package moe.fuqiuluo.portal.ui.mock

import android.content.ClipData
import android.content.ClipboardManager
import android.location.LocationManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.Navigation
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.fuqiuluo.portal.R
import moe.fuqiuluo.portal.databinding.FragmentMockBinding
import moe.fuqiuluo.portal.ext.accuracy
import moe.fuqiuluo.portal.ext.altitude
import moe.fuqiuluo.portal.ext.disableFusedProvider
import moe.fuqiuluo.portal.ext.historicalLocations
import moe.fuqiuluo.portal.ext.rawHistoricalLocations
import moe.fuqiuluo.portal.ext.selectLocation
import moe.fuqiuluo.portal.service.MockServiceHelper
import moe.fuqiuluo.portal.ui.viewmodel.MockServiceViewModel
import moe.fuqiuluo.xposed.utils.PortalIpc

class MockFragment : Fragment() {
    private var _binding: FragmentMockBinding? = null
    private val binding get() = _binding!!

    private val mockServiceViewModel by activityViewModels<MockServiceViewModel>()
    private var serviceRunning = false
    private var currentServiceState = ServiceState.CHECKING
    private var locationAdapter: HistoricalLocationAdapter? = null
    private var serviceStateGeneration = 0L

    private enum class ServiceState {
        CHECKING,
        NOT_CONNECTED,
        READY,
        RUNNING,
        FUSED_DEGRADED,
        STARTING,
        STOPPING,
        START_FAILED,
        STOP_FAILED,
        LOCATION_UPDATE_FAILED
    }

    private enum class StopResult {
        STOPPED,
        NOT_RUNNING,
        FAILED
    }

    private enum class StartResult {
        STARTED,
        FAILED_STOPPED,
        FAILED_UNCERTAIN,
    }

    private data class ServiceSnapshot(
        val state: ServiceState,
        val runningLocation: Pair<Double, Double>? = null,
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMockBinding.inflate(inflater, container, false)

        binding.historicalLocationList.contentDescription =
            getString(R.string.v2_saved_locations_description)

        binding.switchMock.setOnClickListener {
            if (serviceRunning) {
                tryCloseService(it as MaterialButton)
            } else {
                tryOpenService(it as MaterialButton)
            }
        }

        binding.btnServiceRetry.setOnClickListener { refreshServiceStatus(reconnect = true) }
        binding.btnServiceTroubleshoot.setOnClickListener { showTroubleshooting() }

        binding.fabAddLocation.setOnClickListener {
            if (serviceRunning) {
                showToast(getString(R.string.v2_stop_before_change))
            } else {
                Navigation.findNavController(it).navigate(R.id.nav_select_location)
            }
        }

        val selectedLocation = requireContext().selectLocation
            ?: mockServiceViewModel.selectedLocation
        mockServiceViewModel.selectedLocation = selectedLocation
        renderSelectedLocation(selectedLocation)

        val locations = requireContext().historicalLocations
        binding.historyEmpty.visibility = if (locations.isEmpty()) View.VISIBLE else View.GONE
        binding.historicalLocationList.visibility = if (locations.isEmpty()) View.GONE else View.VISIBLE

        val historicalLocationAdapter = HistoricalLocationAdapter(
            locations.sortedBy { it.name }.toMutableList()
        ) { location, isLongClick ->
            if (isLongClick) {
                showLocationActions(location)
                return@HistoricalLocationAdapter
            }

            if (location == mockServiceViewModel.selectedLocation) return@HistoricalLocationAdapter

            if (serviceRunning) {
                confirmSwitchRunningLocation(location)
                return@HistoricalLocationAdapter
            }

            selectLocation(location)
            showToast(getString(R.string.v2_location_selected))
        }
        historicalLocationAdapter.selected = selectedLocation
        locationAdapter = historicalLocationAdapter

        val recyclerView = binding.historicalLocationList
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = historicalLocationAdapter
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            0,
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.adapterPosition
                if (position == RecyclerView.NO_POSITION) return

                confirmDeleteLocation(historicalLocationAdapter[position]) {
                    historicalLocationAdapter.notifyItemChanged(position)
                }
            }
        }).attachToRecyclerView(recyclerView)

        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        monitorFusedState()
    }

    private fun tryOpenService(button: MaterialButton) {
        val selectedLocation = mockServiceViewModel.selectedLocation ?: run {
            if (mockServiceViewModel.locationManager == null || !MockServiceHelper.isServiceInit()) {
                renderServiceState(ServiceState.NOT_CONNECTED)
            } else {
                renderServiceState(ServiceState.READY)
            }
            showToast(getString(R.string.v2_select_location_first))
            return
        }

        val locationManager = mockServiceViewModel.locationManager ?: run {
            renderServiceState(ServiceState.NOT_CONNECTED)
            showToast(getString(R.string.v2_service_not_connected))
            return
        }

        if (!MockServiceHelper.isServiceInit()) {
            renderServiceState(ServiceState.NOT_CONNECTED)
            showToast(getString(R.string.v2_service_not_connected))
            return
        }

        val context = requireContext()
        val altitude = context.altitude
        val accuracy = context.accuracy
        val disableFused = context.disableFusedProvider
        renderServiceState(ServiceState.STARTING)
        button.isEnabled = false
        val operationGeneration = serviceStateGeneration

        viewLifecycleOwner.lifecycleScope.launch {
            val startResult = withContext(Dispatchers.IO) {
                val started = runCatching {
                    MockServiceHelper.startFixedMock(
                        locationManager,
                        selectedLocation.lat,
                        selectedLocation.lon,
                        altitude,
                        accuracy,
                        disableFused,
                    )
                }.getOrDefault(false)
                if (started) StartResult.STARTED else {
                    when (runCatching { MockServiceHelper.getMockStatus(locationManager) }.getOrNull()) {
                        false -> StartResult.FAILED_STOPPED
                        else -> StartResult.FAILED_UNCERTAIN
                    }
                }
            }

            if (_binding == null || serviceStateGeneration != operationGeneration) return@launch

            button.isEnabled = true
            when (startResult) {
                StartResult.STARTED -> {
                    renderServiceState(ServiceState.RUNNING, selectedLocation.lat to selectedLocation.lon)
                    showToast(getString(R.string.v2_fixed_started))
                }
                StartResult.FAILED_STOPPED -> {
                    renderServiceState(ServiceState.START_FAILED)
                    showToast(getString(R.string.v2_start_failed))
                }
                StartResult.FAILED_UNCERTAIN -> {
                    renderServiceState(ServiceState.STOP_FAILED)
                    showToast(getString(R.string.v2_start_failed))
                }
            }
        }
    }

    private fun tryCloseService(button: MaterialButton, onStopped: (() -> Unit)? = null) {
        val locationManager = mockServiceViewModel.locationManager ?: run {
            renderServiceState(ServiceState.NOT_CONNECTED)
            showToast(getString(R.string.v2_service_not_connected))
            return
        }

        if (!MockServiceHelper.isServiceInit()) {
            renderServiceState(ServiceState.NOT_CONNECTED)
            showToast(getString(R.string.v2_service_not_connected))
            return
        }

        renderServiceState(ServiceState.STOPPING)
        button.isEnabled = false
        val operationGeneration = serviceStateGeneration

        viewLifecycleOwner.lifecycleScope.launch {
            val stopResult = withContext(Dispatchers.IO) {
                runCatching {
                    when (MockServiceHelper.getMockStatus(locationManager)) {
                        false -> StopResult.NOT_RUNNING
                        true -> if (MockServiceHelper.tryCloseMock(locationManager)) {
                            StopResult.STOPPED
                        } else {
                            StopResult.FAILED
                        }
                        null -> StopResult.FAILED
                    }
                }.getOrDefault(StopResult.FAILED)
            }

            if (_binding == null || serviceStateGeneration != operationGeneration) return@launch

            button.isEnabled = true
            when (stopResult) {
                StopResult.STOPPED -> {
                    renderServiceState(ServiceState.READY)
                    showToast(getString(R.string.v2_fixed_stopped))
                    onStopped?.invoke()
                }

                StopResult.NOT_RUNNING -> {
                    renderServiceState(ServiceState.READY)
                    showToast(getString(R.string.v2_service_not_running))
                    onStopped?.invoke()
                }

                StopResult.FAILED -> {
                    renderServiceState(ServiceState.STOP_FAILED)
                    showToast(getString(R.string.v2_stop_failed))
                }
            }
        }
    }

    private fun updateRunningLocation(location: HistoricalLocation) {
        val locationManager = mockServiceViewModel.locationManager
        if (locationManager == null || !MockServiceHelper.isServiceInit()) {
            renderServiceState(ServiceState.NOT_CONNECTED)
            showToast(getString(R.string.v2_service_not_connected))
            return
        }

        val operationGeneration = serviceStateGeneration
        viewLifecycleOwner.lifecycleScope.launch {
            val updated = withContext(Dispatchers.IO) {
                runCatching {
                    MockServiceHelper.updateFixedLocation(locationManager, location.lat, location.lon)
                }.getOrDefault(false)
            }

            if (_binding == null || serviceStateGeneration != operationGeneration) return@launch

            if (updated) {
                renderServiceState(ServiceState.RUNNING)
                showToast(getString(R.string.v2_location_updated))
            } else {
                renderServiceState(ServiceState.LOCATION_UPDATE_FAILED)
                showToast(getString(R.string.v2_location_update_failed))
            }
        }
    }

    private fun refreshServiceStatus(reconnect: Boolean = false) {
        if (_binding == null) return
        if (currentServiceState == ServiceState.STARTING || currentServiceState == ServiceState.STOPPING) return
        renderServiceState(ServiceState.CHECKING)
        val expectsFused = !requireContext().disableFusedProvider
        val requestGeneration = serviceStateGeneration

        viewLifecycleOwner.lifecycleScope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                val locationManager = mockServiceViewModel.locationManager
                // The key exchange only happens once at startup; retry it after the user fixes LSPosed.
                if (reconnect && locationManager != null && !MockServiceHelper.isServiceInit()) {
                    runCatching { MockServiceHelper.tryInitService(locationManager) }
                }
                readServiceSnapshot(locationManager, expectsFused)
            }

            if (_binding != null && serviceStateGeneration == requestGeneration) {
                renderServiceState(snapshot.state, snapshot.runningLocation)
            }
        }
    }

    private fun monitorFusedState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (isActive) {
                    delay(5_000)
                    if (_binding == null || currentServiceState !in setOf(
                            ServiceState.RUNNING, ServiceState.FUSED_DEGRADED,
                        )
                    ) continue
                    val expectsFused = !requireContext().disableFusedProvider
                    val requestGeneration = serviceStateGeneration
                    val snapshot = withContext(Dispatchers.IO) {
                        val manager = mockServiceViewModel.locationManager
                        readServiceSnapshot(manager, expectsFused)
                    }
                    if (_binding != null && serviceStateGeneration == requestGeneration && currentServiceState in setOf(
                            ServiceState.RUNNING, ServiceState.FUSED_DEGRADED,
                        )
                    ) renderServiceState(snapshot.state, snapshot.runningLocation)
                }
            }
        }
    }

    private fun readServiceSnapshot(manager: LocationManager?, expectsFused: Boolean): ServiceSnapshot {
        if (manager == null || !MockServiceHelper.isServiceInit()) {
            return ServiceSnapshot(ServiceState.NOT_CONNECTED)
        }
        return runCatching {
            when (MockServiceHelper.getMockStatus(manager)) {
                true -> {
                    val state = if (expectsFused && MockServiceHelper.isFixedFusedDisabled(manager) != false) {
                        ServiceState.FUSED_DEGRADED
                    } else ServiceState.RUNNING
                    ServiceSnapshot(state, runCatching { MockServiceHelper.getLocation(manager) }.getOrNull())
                }
                false -> ServiceSnapshot(ServiceState.READY)
                null -> ServiceSnapshot(ServiceState.NOT_CONNECTED)
            }
        }.getOrDefault(ServiceSnapshot(ServiceState.NOT_CONNECTED))
    }

    private fun renderSelectedLocation(location: HistoricalLocation?) {
        if (_binding == null) return

        if (location == null) {
            binding.mockLocationName.text = getString(R.string.v2_no_location_selected)
            binding.mockLocationAddress.text = getString(R.string.v2_no_address)
            binding.mockLocationLatlon.text = getString(R.string.v2_no_coordinates)
            binding.fabAddLocation.text = getString(R.string.v2_choose_location)
            binding.fabAddLocation.contentDescription = getString(R.string.v2_choose_location)
            return
        }

        binding.mockLocationName.text = location.name
        binding.fabAddLocation.text = getString(R.string.v2_change_location)
        binding.mockLocationAddress.text = location.address.ifBlank { getString(R.string.v2_no_address) }
        binding.mockLocationLatlon.text = getString(
            R.string.v2_coordinates_format,
            location.lat,
            location.lon
        )
        binding.fabAddLocation.contentDescription = getString(R.string.v2_change_location)
    }

    private fun renderServiceState(state: ServiceState, runningLocation: Pair<Double, Double>? = null) {
        if (_binding == null) return
        serviceStateGeneration++
        currentServiceState = state

        serviceRunning = when (state) {
            ServiceState.RUNNING,
            ServiceState.FUSED_DEGRADED,
            ServiceState.LOCATION_UPDATE_FAILED,
            ServiceState.STOPPING,
            ServiceState.STOP_FAILED -> true

            else -> false
        }

        val (title, detail) = when (state) {
            ServiceState.CHECKING -> R.string.v2_service_checking to R.string.v2_service_checking_detail
            ServiceState.NOT_CONNECTED -> R.string.v2_service_not_connected to R.string.v2_service_not_connected_detail
            ServiceState.READY -> R.string.v2_service_ready to R.string.v2_service_ready_detail
            ServiceState.RUNNING -> R.string.v2_service_running to R.string.v2_service_running_detail
            ServiceState.FUSED_DEGRADED -> R.string.v2_fused_degraded to R.string.v2_fused_degraded_detail
            ServiceState.STARTING -> R.string.v2_service_starting to R.string.v2_service_starting_detail
            ServiceState.STOPPING -> R.string.v2_service_stopping to R.string.v2_service_stopping_detail
            ServiceState.START_FAILED -> R.string.v2_start_failed to when (MockServiceHelper.lastFixedStartBlockedReason) {
                PortalIpc.BLOCKED_TELEPHONY -> R.string.v2_start_blocked_telephony
                PortalIpc.BLOCKED_XIAOMI_FUSED -> R.string.v2_start_blocked_xiaomi_fused
                else -> R.string.v2_start_failed_detail
            }
            ServiceState.STOP_FAILED -> R.string.v2_stop_failed to R.string.v2_stop_failed_detail
            ServiceState.LOCATION_UPDATE_FAILED ->
                R.string.v2_location_update_failed to R.string.v2_location_update_failed_detail
        }

        binding.serviceStatus.text = getString(title)
        val selected = mockServiceViewModel.selectedLocation?.let { it.lat to it.lon }
        val consistency = if (state == ServiceState.RUNNING || state == ServiceState.FUSED_DEGRADED) {
            ActiveLocationConsistency.compare(selected, runningLocation)
        } else ActiveLocationConsistency.MATCH
        val locationWarning = when (consistency) {
            ActiveLocationConsistency.MATCH -> null
            ActiveLocationConsistency.MISMATCH -> R.string.v2_running_location_mismatch
            ActiveLocationConsistency.UNAVAILABLE -> R.string.v2_running_location_unverified
        }
        binding.serviceStatusDetail.text = getString(detail) +
            (locationWarning?.let { "\n" + getString(it) } ?: "")
        binding.switchMock.text = getString(
            if (serviceRunning) R.string.v2_stop_fixed else R.string.v2_start_fixed
        )
        ContextCompat.getDrawable(
            requireContext(),
            if (serviceRunning) R.drawable.rounded_play_disabled_24 else R.drawable.rounded_play_arrow_24
        )?.let { binding.switchMock.icon = it }
        binding.switchMock.contentDescription = getString(
            if (serviceRunning) R.string.v2_stop_fixed_description else R.string.v2_start_fixed_description
        )
        binding.switchMock.isEnabled = state != ServiceState.CHECKING &&
            state != ServiceState.STARTING &&
            state != ServiceState.STOPPING

        binding.serviceActions.visibility = when (state) {
            ServiceState.NOT_CONNECTED,
            ServiceState.START_FAILED,
            ServiceState.STOP_FAILED -> View.VISIBLE
            else -> View.GONE
        }
        binding.btnServiceTroubleshoot.visibility =
            if (state == ServiceState.NOT_CONNECTED || state == ServiceState.START_FAILED) View.VISIBLE else View.GONE
        binding.serviceStatusCard.strokeColor = ContextCompat.getColor(
            requireContext(),
            when (state) {
                ServiceState.RUNNING -> if (locationWarning == null) R.color.primary else R.color.v2_status_warning
                ServiceState.NOT_CONNECTED,
                ServiceState.FUSED_DEGRADED,
                ServiceState.START_FAILED,
                ServiceState.STOP_FAILED,
                ServiceState.LOCATION_UPDATE_FAILED -> R.color.v2_status_warning
                else -> R.color.portal_location_detail_background
            }
        )
        binding.serviceStatusCard.strokeWidth = resources.getDimensionPixelSize(
            if (state == ServiceState.RUNNING || binding.serviceActions.visibility == View.VISIBLE ||
                state == ServiceState.FUSED_DEGRADED || state == ServiceState.LOCATION_UPDATE_FAILED
            ) R.dimen.v2_status_stroke_emphasis else R.dimen.v2_status_stroke
        )
        binding.mockPrivacyTip.text = getString(
            if (serviceRunning) R.string.v2_running_tip else R.string.v2_ready_tip
        )
        // Changing the point requires a stop first; make that visible instead of failing on tap.
        binding.fabAddLocation.isEnabled = !serviceRunning
    }

    private fun selectLocation(location: HistoricalLocation) {
        renderSelectedLocation(location)
        mockServiceViewModel.selectedLocation = location
        requireContext().selectLocation = location
        locationAdapter?.selected = location
    }

    private fun confirmSwitchRunningLocation(location: HistoricalLocation) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.v2_switch_location_title, location.name))
            .setMessage(R.string.v2_switch_location_message)
            .setPositiveButton(R.string.v2_switch_location_positive) { _, _ ->
                if (_binding == null || !serviceRunning) return@setPositiveButton
                tryCloseService(binding.switchMock) {
                    if (_binding == null) return@tryCloseService
                    selectLocation(location)
                    tryOpenService(binding.switchMock)
                }
            }
            .setNegativeButton(R.string.v2_cancel, null)
            .show()
    }

    private fun showLocationActions(location: HistoricalLocation) {
        val actions = arrayOf(
            getString(R.string.v2_location_actions_copy),
            getString(R.string.v2_location_actions_delete),
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(location.name)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> {
                        val clipboard = requireContext().getSystemService(ClipboardManager::class.java)
                        clipboard?.setPrimaryClip(
                            ClipData.newPlainText(
                                location.name,
                                getString(R.string.v2_coordinates_plain, location.lat, location.lon)
                            )
                        )
                        showToast(getString(R.string.v2_coordinates_copied))
                    }
                    1 -> confirmDeleteLocation(location) {}
                }
            }
            .show()
    }

    private fun confirmDeleteLocation(location: HistoricalLocation, onCancel: () -> Unit) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.v2_delete_location_title)
            .setMessage(getString(R.string.v2_delete_location_message, location.name))
            .setPositiveButton(R.string.v2_delete) { _, _ ->
                val adapter = locationAdapter ?: return@setPositiveButton
                val position = (0 until adapter.itemCount).firstOrNull { adapter[it] == location }
                    ?: return@setPositiveButton
                adapter.removeItem(position)
                requireContext().rawHistoricalLocations = requireContext().rawHistoricalLocations.toMutableSet().apply {
                    removeIf {
                        runCatching {
                            HistoricalLocation.fromString(it).name == location.name
                        }.getOrDefault(false)
                    }
                }
                if (_binding != null) {
                    binding.historyEmpty.visibility =
                        if (adapter.itemCount == 0) View.VISIBLE else View.GONE
                    binding.historicalLocationList.visibility =
                        if (adapter.itemCount == 0) View.GONE else View.VISIBLE
                }
                showToast(getString(R.string.v2_location_deleted))
            }
            .setNegativeButton(R.string.v2_cancel) { _, _ -> onCancel() }
            .setOnCancelListener { onCancel() }
            .show()
    }

    private fun showTroubleshooting() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.v2_troubleshoot_title)
            .setMessage(R.string.v2_troubleshoot_message)
            .setPositiveButton(R.string.v2_troubleshoot_ok, null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) {
            requireContext().selectLocation?.let {
                mockServiceViewModel.selectedLocation = it
                renderSelectedLocation(it)
                locationAdapter?.selected = it
            }
            refreshServiceStatus()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        serviceStateGeneration++
        currentServiceState = ServiceState.CHECKING
        locationAdapter = null
        _binding = null
    }

    private fun showToast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }
}
