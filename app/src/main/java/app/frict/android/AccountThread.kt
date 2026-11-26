package app.frict.android

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

/** One thread for every account operation, so no two touch the keys or the store at once. */
val accountThread: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

fun approvals(account: Account) = Approvals(
    account = account,
    api = DeviceApi("https://${account.host}"),
    deviceSigner = keystoreSigner(account.keys.device),
    unixSeconds = { System.currentTimeMillis() / 1000 },
)
