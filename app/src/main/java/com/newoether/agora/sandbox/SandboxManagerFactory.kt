package com.newoether.agora.sandbox

/**
 * Provides flavor-specific access to the Sandbox manager.
 *
 * The F-Droid implementation returns one process-shared manager. Consumers borrow that reference
 * and must not shut it down when their own lifecycle ends. The Play flavor returns a no-op stub.
 */
interface SandboxManagerFactory {
    /** Return the manager for this flavor. The returned instance may be process-shared. */
    fun create(): SandboxManager

    /** Whether the sandbox feature is available in this build. */
    fun isAvailable(): Boolean
}
