// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.util

import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.project.Project

/**
 * Whether the plugin may act on a project.
 *
 * The scanner is a process spawned with the project as its working directory,
 * reading whatever the tree contains. The VS Code extension refuses untrusted
 * workspaces for exactly that reason (`untrustedWorkspaces.supported: false`), and
 * this is the IntelliJ equivalent.
 *
 * It matters more with every pillar added: cnspec will execute policies, read
 * project-provided bundles and connect to targets with credentials, so the gate
 * belongs here now rather than being retrofitted then.
 *
 * Fails **closed**: anything unexpected is treated as untrusted, because the cost of
 * wrongly scanning an untrusted project is higher than the cost of a user clicking
 * "Trust Project".
 */
object ProjectTrust {

    fun isTrusted(project: Project): Boolean =
        runCatching { TrustedProjects.isProjectTrusted(project) }.getOrDefault(false)
}
