package borg.trikeshed.forge

import borg.trikeshed.forge.cli.ForgeCli

/**
 * Main entry point for Forge CLI
 */
fun main(args: Array<String>) {
    val workspace = ForgeWorkspaceImpl()
    val cli = ForgeCli(workspace)
    cli.main(args)
}