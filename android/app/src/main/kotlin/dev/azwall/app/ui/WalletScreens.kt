package dev.azwall.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.azwall.app.AppViewModel
import dev.azwall.app.Auth
import dev.azwall.app.BuildConfig
import dev.azwall.app.ui.browser.BrowserScreen
import dev.azwall.app.ui.browser.DappRequests
import dev.azwall.app.ui.browser.AppDrawer
import dev.azwall.app.ui.browser.MenuItem
import dev.azwall.app.ui.browser.MainTab
import dev.azwall.app.ui.browser.rememberBrowser
import dev.azwall.app.ui.browser.ConnectionsSheet
import dev.azwall.app.ui.components.copySecret
import dev.azwall.app.ui.home.HomeActions
import dev.azwall.app.ui.home.HomeScreen
import dev.azwall.app.ui.receive.ReceiveScreen
import dev.azwall.app.ui.send.ProvingOverlay
import dev.azwall.app.ui.send.TransferMode
import dev.azwall.app.ui.send.TransferScreen
import dev.azwall.core.wallet.TokenView
import dev.azwall.app.ui.tokens.TokenStudioScreen
import dev.azwall.app.ui.tokens.TokenStudioActions
import dev.azwall.app.ui.settings.PhraseDialog
import dev.azwall.app.ui.settings.SecretKeyDialog
import dev.azwall.app.ui.settings.SettingsActions
import dev.azwall.app.ui.settings.SettingsInfo
import dev.azwall.app.ui.settings.SettingsScreen
import dev.azwall.app.ui.sheets.AccountsSheet
import dev.azwall.app.ui.sheets.AddTokenSheet
import dev.azwall.app.ui.sheets.NetworksSheet
import dev.azwall.core.wallet.Amounts
import dev.azwall.core.wallet.Fees
import dev.azwall.core.wallet.WalletSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class Route { HOME, SEND, SHIELD, RECEIVE, SETTINGS, TOKENS }

private enum class Sheet { NONE, NETWORKS, ACCOUNTS, ADD_TOKEN, CONNECTIONS }

/** How often balances are refreshed while the wallet is on screen. */
private const val REFRESH_MS = 20_000L

/** What the fingerprint prompt says when a confirmed transaction is about to be proven and sent. */
private const val CONFIRM = "Confirm the transaction"

@Composable
fun WalletScreens(vm: AppViewModel, session: WalletSession, busy: Boolean, auth: (String) -> Auth, dismissMessage: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(MainTab.WALLET) }
    var route by rememberSaveable { mutableStateOf(Route.HOME) }
    var sheet by rememberSaveable { mutableStateOf(Sheet.NONE) }
    val proving by session.transactions.proving.collectAsStateWithLifecycle()
    val state by session.state.collectAsStateWithLifecycle()
    val browser = rememberBrowser(session, vm.approvals)
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val openMenu: () -> Unit = { scope.launch { drawer.open() } }
    AutoRefresh(session)
    ModalNavigationDrawer(
        drawerState = drawer,
        // Swiping open would fight with the web page's own gestures: the menu opens from its button only.
        gesturesEnabled = drawer.isOpen,
        drawerContent = {
            AppDrawer(state, tab) { item ->
                scope.launch { drawer.close() }
                when (item) {
                    MenuItem.WALLET -> { tab = MainTab.WALLET; route = Route.HOME }
                    MenuItem.EXPLORE -> tab = MainTab.EXPLORE
                    MenuItem.SETTINGS -> { tab = MainTab.WALLET; route = Route.SETTINGS }
                    MenuItem.TOKENS -> { tab = MainTab.WALLET; route = Route.TOKENS }
                    MenuItem.LOCK -> vm.lock()
                }
            }
        },
    ) {
        // dApp requests dock under the page, which shrinks but stays usable above them.
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                if (tab == MainTab.EXPLORE) {
                    val sessions by session.dapps.sessions.collectAsStateWithLifecycle()
                    val engine by vm.searchEngine.collectAsStateWithLifecycle()
                    BrowserScreen(browser, state.network, sessions, engine, onMenu = openMenu, onConnections = { sheet = Sheet.CONNECTIONS })
                } else {
                    WalletRoutes(vm, session, busy, auth, dismissMessage, route, { route = it }, { sheet = it }, openMenu)
                }
            }
            DappRequests(vm, browser, auth(CONFIRM), settle = session.transactions::settle, onShowPage = { tab = MainTab.EXPLORE })
        }
    }
    WalletSheets(vm, session, sheet, busy, auth) { sheet = Sheet.NONE }
    proving?.let { ProvingOverlay(it, onCancel = session.transactions::cancel) }
}

@Composable
private fun WalletRoutes(
    vm: AppViewModel,
    session: WalletSession,
    busy: Boolean,
    auth: (String) -> Auth,
    dismissMessage: () -> Unit,
    route: Route,
    go: (Route) -> Unit,
    open: (Sheet) -> Unit,
    openMenu: () -> Unit,
) {
    val state by session.state.collectAsStateWithLifecycle()
    val nodes by vm.nodes.collectAsStateWithLifecycle()
    BackHandler(enabled = route != Route.HOME) { go(Route.HOME) }
    val home = { go(Route.HOME) }
    when (route) {
        Route.HOME -> HomeScreen(state, nodes[state.network.id]?.compatible, homeActions(vm, session, auth, go, open, openMenu))
        Route.SEND, Route.SHIELD -> TransferScreen(
            mode = if (route == Route.SEND) TransferMode.SEND else TransferMode.SHIELD,
            tokens = state.tokens,
            me = state.account?.address.orEmpty(),
            network = state.network.id,
            networkLabel = state.network.label,
            fee = Fees.line(state.check, state.feeJuice),
            busy = busy,
            onBack = home,
        ) { kind, token, to, amount ->
            vm.sign(auth(CONFIRM), "Sent. It will appear in your balance once in a block.", onAuthorized = home) {
                it.transactions.send(kind, token.info, to, amount)
            }
        }
        Route.RECEIVE -> state.account?.let { ReceiveScreen(it.alias, it.address, state.network.label, home, dismissMessage) }
        Route.SETTINGS -> SettingsRoute(vm, session, busy, auth, openMenu)
        Route.TOKENS -> TokenStudioScreen(state, busy, TokenStudioActions(
            onMenu = openMenu,
            onCreate = { name, symbol, decimals -> vm.sign(auth(CONFIRM), "Token creation sent.") { it.transactions.deployTestToken(name, symbol, decimals) } },
            onMint = { token, amount, private, to, onAuthorized -> mint(vm, auth(CONFIRM), token, amount, private, to, onAuthorized) },
        ))
    }
}

private fun homeActions(vm: AppViewModel, session: WalletSession, auth: (String) -> Auth, go: (Route) -> Unit, open: (Sheet) -> Unit, menu: () -> Unit) = HomeActions(
    onMenu = menu,
    onNetworks = { open(Sheet.NETWORKS) },
    onAccounts = { open(Sheet.ACCOUNTS) },
    onSettings = { go(Route.SETTINGS) },
    onSend = { go(Route.SEND) },
    onReceive = { go(Route.RECEIVE) },
    onShield = { go(Route.SHIELD) },
    onAddToken = { open(Sheet.ADD_TOKEN) },
    onFollowFound = { token -> session.followFound(token) },
    onChoosePrimary = { address -> session.choosePrimary(address) },
    onDismissFound = { token -> session.dismissFound(token) },
    onActivate = { vm.sign(auth(CONFIRM), "Account activation sent.") { it.transactions.deployAccount() } },
    onClaimSecret = { secret -> vm.run { it.addClaimSecret(secret) } },
    onClaimFeeJuice = {
        session.state.value.claimable?.let { deposit -> vm.sign(auth(CONFIRM), "Fee Juice claim sent.") { it.transactions.claimFeeJuice(deposit) } }
    },
    onRefresh = { vm.run { it.refresh() } },
    onAcknowledgeReset = session::acknowledgeChainReset,
    onRemoveToken = session::removeToken,
    onCopied = {},
)

@Composable
private fun AutoRefresh(session: WalletSession) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(session) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(REFRESH_MS)
                session.refresh()
            }
        }
    }
}

@Composable
private fun WalletSheets(vm: AppViewModel, session: WalletSession, sheet: Sheet, busy: Boolean, auth: (String) -> Auth, close: () -> Unit) {
    val state by session.state.collectAsStateWithLifecycle()
    val networks by vm.networks.collectAsStateWithLifecycle()
    when (sheet) {
        Sheet.NONE -> Unit
        Sheet.NETWORKS -> NetworksSheet(networks, state.network.id, onSelect = { vm.switchNetwork(it); close() }, onDismiss = close)
        Sheet.ACCOUNTS -> AccountsSheet(
            state.accounts,
            state.selected,
            onSelect = { session.select(it); close() },
            onCreate = { alias, key -> vm.addAccount(alias, key, auth("Save the new account")); close() },
            onDismiss = close,
        )
        Sheet.ADD_TOKEN -> AddTokenSheet(state.network.id, busy, onAdd = { address -> vm.run("Token added.") { it.addToken(address) }; close() }, onDismiss = close)
        Sheet.CONNECTIONS -> {
            val sessions by session.dapps.sessions.collectAsStateWithLifecycle()
            ConnectionsSheet(sessions, state.network.label, onDisconnect = { id -> vm.run { it.dapps.disconnect(id) } }, onDismiss = close)
        }
    }
}

@Composable
private fun SettingsRoute(vm: AppViewModel, session: WalletSession, busy: Boolean, auth: (String) -> Auth, menu: () -> Unit) {
    val state by session.state.collectAsStateWithLifecycle()
    val nodes by vm.nodes.collectAsStateWithLifecycle()
    val proverReady by vm.proverReady.collectAsStateWithLifecycle()
    val engine by vm.searchEngine.collectAsStateWithLifecycle()
    var revealed by remember { mutableStateOf<String?>(null) }
    var phrase by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val info = remember(nodes, proverReady, state.network, engine) {
        SettingsInfo(BuildConfig.VERSION_NAME, coreVersion(), releases(), nodes[state.network.id], proverReady == state.network.id, engine, vm.hasRecoveryPhrase)
    }
    val network = state.network.id
    SettingsScreen(state, info, busy, SettingsActions(
        onMenu = menu,
        onRename = { name -> state.account?.let { vm.renameAccount(it.address, name, auth("Rename the account")) } },
        onRevealKey = { scope.launch { revealed = runCatching { vm.revealKey(state.selected!!, auth("Show the secret key")) }.getOrNull() } },
        onCheckNode = { vm.checkNode(network) },
        onLoadProver = { vm.loadProver(network) },
        onLock = { vm.lock() },
        onWipe = { vm.wipe(auth("Remove the wallet")) },
        onSearchEngine = vm::chooseSearchEngine,
        onRevealPhrase = { scope.launch { phrase = runCatching { vm.revealPhrase(auth("Show the recovery phrase")) }.getOrNull() } },
    ))
    phrase?.let { PhraseDialog(it) { phrase = null } }
    revealed?.let { key -> SecretKeyDialog(key, onCopy = { copySecret(context, key) }, onDismiss = { revealed = null }) }
}

private fun mint(vm: AppViewModel, auth: Auth, token: TokenView, amount: String, private: Boolean, to: String, onAuthorized: () -> Unit) =
    vm.sign(auth, "Mint sent.", onAuthorized) { session ->
        session.transactions.mint(token.info, Amounts.parse(amount, token.info.decimals), private, to)
    }

private fun coreVersion(): String = runCatching { dev.azwall.core.ffi.coreVersion() }.getOrDefault("?")

private fun releases(): List<String> = runCatching { dev.azwall.core.ffi.supportedBarretenbergReleases() }.getOrDefault(emptyList())
