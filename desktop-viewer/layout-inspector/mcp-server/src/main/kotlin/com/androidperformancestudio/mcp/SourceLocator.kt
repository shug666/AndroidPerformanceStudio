package com.androidperformancestudio.mcp

import java.io.File
import kotlin.math.max

class SourceLocator(
    private val maxFiles: Int = 20_000,
    private val maxFileBytes: Long = 2_000_000L,
) {
    fun locate(
        projectRoot: String,
        viewClassName: String? = null,
        resourceName: String? = null,
        text: String? = null,
        activityClass: String? = null,
        packageName: String? = null,
        sourceHint: String? = null,
        contentDescription: String? = null,
        ancestors: List<NodeSummary> = emptyList(),
        siblings: List<NodeSummary> = emptyList(),
    ): LocateResult {
        val root = File(projectRoot)
        val entry = resourceName?.entryName()
        val anchors = LocateAnchors(
            packageName = packageName,
            activityClass = activityClass,
            viewClassName = viewClassName,
            resourceName = resourceName,
            resourceEntry = entry,
            text = text,
            sourceHint = sourceHint,
        )
        if (!root.isDirectory) {
            return LocateResult(emptyList(), projectRoot, anchors, 0)
        }

        val index = indexProject(root)
        val candidates = mutableListOf<LocateCandidate>()

        sourceHint?.let { candidates += locateBySourceHint(it, index) }

        val scope = resolveActivityScope(activityClass, index)
        val ancestorLayouts = resolveAncestorLayouts(ancestors, index)
        val inferredLayouts = scope.inferredLayouts + ancestorLayouts

        if (entry != null) {
            candidates += locateLayoutXmlResourceIdInLayouts(entry, inferredLayouts, index)
            candidates += locateLayoutXmlResourceIdWithClass(entry, viewClassName, index, ancestorLayouts, siblings)
            candidates += locateTestTag(entry, index)
            candidates += locateRidInActivityFiles(entry, scope.activityFiles)
            candidates += locateRidReference(entry, index)
            candidates += locateValuesIdsXml(entry, index)
        }

        if (viewClassName != null && isCustomView(viewClassName)) {
            candidates += locateLayoutXmlCustomView(viewClassName, index)
            candidates += locateCustomViewUsageInCode(viewClassName, index)
            candidates += locateComposable(viewClassName, index)
            candidates += locateCustomViewClass(viewClassName, index)
        }

        if (!text.isNullOrBlank()) {
            candidates += locateLayoutXmlText(text, index)
            candidates += locateByText(text, index)
        }

        if (!contentDescription.isNullOrBlank()) {
            candidates += locateContentDescription(contentDescription, index)
        }

        activityClass?.let { candidates += locateActivityClass(it, index) }

        val deduped = candidates
            .groupBy { "${it.file}:${it.line}" }
            .map { (_, group) -> group.maxBy { it.confidence } }
        val cmp = compareByDescending<LocateCandidate> { it.confidence }
            .thenBy { it.file.length }
            .thenBy { it.line }
        val xmlTop = deduped.filter { it.file.endsWith(".xml") }.sortedWith(cmp).take(50)
        val codeTop = deduped.filter { !it.file.endsWith(".xml") }.sortedWith(cmp).take(50)
        val merged = xmlTop + codeTop

        return LocateResult(merged, root.absolutePath, anchors, index.scanned)
    }

    private data class ProjectIndex(
        val ktJava: List<File>,
        val layoutXml: List<File>,
        val valuesXml: List<File>,
        val scanned: Int,
    )

    private data class ActivityScope(
        val activityFiles: List<File>,
        val inferredLayouts: Set<String>,
    )

    private fun indexProject(root: File): ProjectIndex {
        val ktJava = mutableListOf<File>()
        val layoutXml = mutableListOf<File>()
        val valuesXml = mutableListOf<File>()
        var scanned = 0
        root.walkTopDown()
            .onEnter { dir -> dir.name !in SKIP_DIRS }
            .forEach { file ->
                if (!file.isFile || scanned >= maxFiles) return@forEach
                if (file.length() > maxFileBytes) return@forEach
                val name = file.name
                when {
                    name.endsWith(".kt") || name.endsWith(".java") -> {
                        if (file.isUnderSourceRoot()) { ktJava.add(file); scanned++ }
                    }
                    name.endsWith(".xml") -> {
                        val parent = file.parentFile?.name.orEmpty()
                        val grand = file.parentFile?.parentFile?.name.orEmpty()
                        when {
                            parent.startsWith("layout") -> { layoutXml.add(file); scanned++ }
                            grand == "values" || parent == "values" -> { valuesXml.add(file); scanned++ }
                        }
                    }
                }
            }
        return ProjectIndex(ktJava, layoutXml, valuesXml, scanned)
    }

    private fun resolveActivityScope(activityClass: String?, index: ProjectIndex): ActivityScope {
        if (activityClass == null) return ActivityScope(emptyList(), emptySet())
        val simple = activityClass.substringAfterLast(".")
        val classPattern = Regex("""\bclass\s+${Regex.escape(simple)}\b""")
        val activityFiles = index.ktJava.filter { f ->
            runCatching { f.useLines { lines -> lines.any { classPattern.containsMatchIn(it) } } }.getOrDefault(false)
        }
        if (activityFiles.isEmpty()) return ActivityScope(emptyList(), emptySet())
        val layoutPattern = Regex("""R\.layout\.(\w+)""")

        val layouts = mutableSetOf<String>()
        activityFiles.forEach { file ->
            val lines = readAllLinesSafe(file) ?: return@forEach
            lines.forEach { line ->
                layoutPattern.findAll(line).forEach { layouts.add(it.groupValues[1]) }
                Regex("""(\w+)Binding\.inflate""").findAll(line).forEach { m ->
                    layouts.add(m.groupValues[1].decamel())
                }
            }
        }
        return ActivityScope(activityFiles, layouts)
    }

    private fun locateBySourceHint(hint: String, index: ProjectIndex): List<LocateCandidate> {
        val parts = hint.split(":")
        val fileName = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return emptyList()
        val line = parts.getOrNull(1)?.toIntOrNull() ?: 1
        val matches = index.ktJava.filter { it.name == fileName || it.path.endsWith(fileName) }
        return matches.map {
            LocateCandidate(it.absolutePath, line, 1.0, "Compose inspection source hint", readLine(it, line))
        }
    }

    private fun locateActivityClass(activityClass: String, index: ProjectIndex): List<LocateCandidate> {
        val simple = activityClass.substringAfterLast(".")
        val pattern = Regex("""\bclass\s+${Regex.escape(simple)}\b""")
        return index.ktJava.flatMap { file ->
            matchLines(file, pattern) { line ->
                LocateCandidate(file.absolutePath, line, 0.55, "Activity/Window class declaration: $simple", readLine(file, line))
            }
        }
    }

    private fun locateCustomViewClass(viewClassName: String, index: ProjectIndex): List<LocateCandidate> {
        val simple = viewClassName.substringAfterLast(".")
        val pattern = Regex("""\bclass\s+${Regex.escape(simple)}\b""")
        return index.ktJava.flatMap { file ->
            matchLines(file, pattern) { line ->
                LocateCandidate(file.absolutePath, line, 0.45, "自定义 View 类定义处（非使用处）: $simple", readLine(file, line))
            }
        }
    }

    private fun locateComposable(viewClassName: String, index: ProjectIndex): List<LocateCandidate> {
        val simple = viewClassName.substringAfterLast(".")
        val funPattern = Regex("""\bfun\s+${Regex.escape(simple)}\b""")
        val results = mutableListOf<LocateCandidate>()
        index.ktJava.forEach { file ->
            val lines = readAllLinesSafe(file) ?: return@forEach
            lines.forEachIndexed { i, line ->
                if (funPattern.containsMatchIn(line)) {
                    val prev = lines.getOrNull(i - 1)?.trim().orEmpty()
                    if ("@Composable" in prev || "@Composable" in line) {
                        results.add(LocateCandidate(file.absolutePath, i + 1, 0.88, "Composable function: $simple", line.trim().take(200)))
                    }
                }
            }
        }
        return results
    }

    private fun locateLayoutXmlCustomView(viewClassName: String, index: ProjectIndex): List<LocateCandidate> {
        val full = Regex("""<${Regex.escape(viewClassName)}\b""")
        val simple = viewClassName.substringAfterLast(".")
        val simpleTag = Regex("""<${Regex.escape(simple)}\b""")
        return index.layoutXml.flatMap { file ->
            matchLines(file, full) { line ->
                LocateCandidate(file.absolutePath, line, 0.90, "布局 XML 使用处: <$viewClassName>", readLine(file, line))
            } + matchLines(file, simpleTag) { line ->
                LocateCandidate(file.absolutePath, line, 0.87, "布局 XML 使用处: <$simple>", readLine(file, line))
            }
        }
    }

    private fun locateCustomViewUsageInCode(viewClassName: String, index: ProjectIndex): List<LocateCandidate> {
        val simple = viewClassName.substringAfterLast(".")
        val constructPattern = Regex("""\b${Regex.escape(simple)}\s*\(""")
        val ctorPrefixes = listOf("public ", "private ", "protected ", "internal ", "fun ")
        val results = mutableListOf<LocateCandidate>()
        index.ktJava.forEach { file ->
            val lines = readAllLinesSafe(file) ?: return@forEach
            lines.forEachIndexed { i, line ->
                if (!constructPattern.containsMatchIn(line)) return@forEachIndexed
                if ("class $simple" in line) return@forEachIndexed
                val trimmed = line.trim()
                if (ctorPrefixes.any { trimmed.startsWith("$it$simple(") }) return@forEachIndexed
                results.add(LocateCandidate(file.absolutePath, i + 1, 0.80, "代码中使用 $simple 的地方", trimmed.take(200)))
            }
        }
        return results
    }

    private fun locateLayoutXmlResourceIdInLayouts(
        entry: String,
        layoutNames: Set<String>,
        index: ProjectIndex,
    ): List<LocateCandidate> {
        if (layoutNames.isEmpty()) return emptyList()
        val pattern = Regex("""@(\+)?id/${Regex.escape(entry)}\b""")
        val targetFiles = index.layoutXml.filter { f -> f.nameWithoutExtension in layoutNames }
        return targetFiles.flatMap { file ->
            matchLines(file, pattern) { line ->
                LocateCandidate(file.absolutePath, line, 0.95, "Layout XML (inferred from Activity) @+id/$entry", readLine(file, line))
            }
        }
    }

    private fun locateLayoutXmlResourceIdWithClass(
        entry: String,
        viewClassName: String?,
        index: ProjectIndex,
        ancestorLayouts: Set<String>,
        siblings: List<NodeSummary>,
    ): List<LocateCandidate> {
        val pattern = Regex("""@(\+)?id/${Regex.escape(entry)}\b""")
        val viewSimple = viewClassName?.substringAfterLast(".")
        val tagRegex = Regex("""<([\w.]+)""")
        val results = mutableListOf<LocateCandidate>()
        index.layoutXml.forEach { file ->
            val lines = readAllLinesSafe(file) ?: return@forEach
            val isAncestorLayout = file.nameWithoutExtension in ancestorLayouts
            val siblingHits = if (siblings.isNotEmpty()) countSiblingHits(lines, siblings) else 0
            lines.forEachIndexed { i, line ->
                if (pattern.containsMatchIn(line)) {
                    val tagLine = (i downTo max(0, i - 3)).map { lines[it] }.firstOrNull { it.trimStart().startsWith("<") }
                    val tag = tagLine?.let { tagRegex.find(it)?.groupValues?.getOrNull(1) }
                    val tagSimple = tag?.substringAfterLast(".")
                    val classMatch = viewSimple != null && tagSimple != null && tagSimple == viewSimple
                    var confidence = if (classMatch) 0.92 else 0.85
                    var reason = if (classMatch) {
                        "Layout XML @+id/$entry (tag <$tagSimple> matches view class)"
                    } else {
                        "Layout XML @+id/$entry"
                    }
                    if (isAncestorLayout) {
                        confidence += 0.05
                        reason += " [祖先布局命中]"
                    }
                    if (siblingHits > 0) {
                        val boost = 0.03 * minOf(siblingHits, 5)
                        confidence += boost
                        reason += " [兄弟签名命中 $siblingHits]"
                    }
                    results.add(LocateCandidate(file.absolutePath, i + 1, confidence, reason, line.trim().take(200)))
                }
            }
        }
        return results
    }

    private fun resolveAncestorLayouts(ancestors: List<NodeSummary>, index: ProjectIndex): Set<String> {
        if (ancestors.isEmpty()) return emptySet()
        val layouts = mutableSetOf<String>()
        ancestors.forEach { a ->
            val res = a.resourceName ?: return@forEach
            val entry = res.entryName()
            if (entry.isBlank()) return@forEach
            val p = Regex("""@(\+)?id/${Regex.escape(entry)}\b""")
            index.layoutXml.forEach { file ->
                if (runCatching { file.useLines { lines -> lines.any { p.containsMatchIn(it) } } }.getOrDefault(false)) {
                    layouts.add(file.nameWithoutExtension)
                }
            }
        }
        return layouts
    }

    private fun countSiblingHits(lines: List<String>, siblings: List<NodeSummary>): Int {
        var hits = 0
        siblings.forEach { s ->
            s.resourceName?.entryName()?.takeIf { it.isNotBlank() }?.let { entry ->
                val p = Regex("""@(\+)?id/${Regex.escape(entry)}\b""")
                if (lines.any { p.containsMatchIn(it) }) hits++
            }
            s.text?.takeIf { it.length >= 2 }?.let { t ->
                val p = Regex("""android:text="${Regex.escape(t)}"""")
                if (lines.any { p.containsMatchIn(it) }) hits++
            }
        }
        return hits
    }

    private fun locateTestTag(entry: String, index: ProjectIndex): List<LocateCandidate> {
        val escaped = Regex.escape(entry)
        val patterns = listOf(
            Regex("""testTag\(["']${escaped}["']\)"""),
            Regex("""testTag\s*=\s*["']${escaped}["']"""),
        )
        return index.ktJava.flatMap { file ->
            patterns.flatMap { p ->
                matchLines(file, p) { line ->
                    LocateCandidate(file.absolutePath, line, 0.82, "testTag(\"$entry\")", readLine(file, line))
                }
            }
        }
    }

    private fun locateRidInActivityFiles(entry: String, activityFiles: List<File>): List<LocateCandidate> {
        if (activityFiles.isEmpty()) return emptyList()
        val pattern = Regex("""\bR\.id\.${Regex.escape(entry)}\b""")
        return activityFiles.flatMap { file ->
            matchLines(file, pattern) { line ->
                LocateCandidate(file.absolutePath, line, 0.85, "R.id.$entry in Activity", readLine(file, line))
            }
        }
    }

    private fun locateRidReference(entry: String, index: ProjectIndex): List<LocateCandidate> {
        val pattern = Regex("""\bR\.id\.${Regex.escape(entry)}\b""")
        return index.ktJava.flatMap { file ->
            matchLines(file, pattern) { line ->
                LocateCandidate(file.absolutePath, line, 0.65, "R.id.$entry reference", readLine(file, line))
            }
        }
    }

    private fun locateValuesIdsXml(entry: String, index: ProjectIndex): List<LocateCandidate> {
        val pattern = Regex("""<item\s+name="${Regex.escape(entry)}"""")
        return index.valuesXml.flatMap { file ->
            matchLines(file, pattern) { line ->
                LocateCandidate(file.absolutePath, line, 0.6, "values/ids.xml name=$entry", readLine(file, line))
            }
        }
    }

    private fun locateLayoutXmlText(text: String, index: ProjectIndex): List<LocateCandidate> {
        if (text.length < 2) return emptyList()
        val escaped = Regex.escape(text)
        val direct = Regex("""android:text="${escaped}"""")
        return index.layoutXml.flatMap { file ->
            matchLines(file, direct) { line ->
                LocateCandidate(file.absolutePath, line, 0.7, "Layout XML android:text=\"$text\"", readLine(file, line))
            }
        }
    }

    private fun locateByText(text: String, index: ProjectIndex): List<LocateCandidate> {
        if (text.length < 2) return emptyList()
        val escaped = Regex.escape(text)
        val stringRes = Regex("""name="${escaped}"""")
        val codeText = Regex("""["']${escaped}["']""")
        return index.valuesXml.flatMap { file ->
            matchLines(file, stringRes) { line ->
                LocateCandidate(file.absolutePath, line, 0.5, "strings.xml name=$text", readLine(file, line))
            }
        } + index.ktJava.flatMap { file ->
            matchLines(file, codeText) { line ->
                LocateCandidate(file.absolutePath, line, 0.45, "Text literal: \"$text\"", readLine(file, line))
            }
        }
    }

    private fun locateContentDescription(cd: String, index: ProjectIndex): List<LocateCandidate> {
        if (cd.length < 2) return emptyList()
        val escaped = Regex.escape(cd)
        val xmlPattern = Regex("""android:contentDescription="${escaped}"""")
        val composePattern = Regex("""contentDescription\s*=\s*["']${escaped}["']""")
        return index.layoutXml.flatMap { file ->
            matchLines(file, xmlPattern) { line ->
                LocateCandidate(file.absolutePath, line, 0.68, "Layout XML contentDescription", readLine(file, line))
            }
        } + index.ktJava.flatMap { file ->
            matchLines(file, composePattern) { lineNum ->
                LocateCandidate(file.absolutePath, lineNum, 0.66, "Compose contentDescription", readLine(file, lineNum))
            }
        }
    }

    private fun matchLines(file: File, pattern: Regex, mapper: (Int) -> LocateCandidate): List<LocateCandidate> {
        val results = mutableListOf<LocateCandidate>()
        try {
            file.useLines { lines ->
                lines.forEachIndexed { index, line ->
                    if (pattern.containsMatchIn(line)) {
                        results.add(mapper(index + 1))
                    }
                }
            }
        } catch (_: Throwable) {
        }
        return results
    }

    private fun readAllLinesSafe(file: File): List<String>? =
        runCatching { file.readLines() }.getOrNull()

    private fun readLine(file: File, line: Int): String? =
        runCatching {
            file.useLines { lines ->
                lines.elementAtOrNull(line - 1)?.trim()?.take(200)
            }
        }.getOrNull()

    private fun isCustomView(className: String?): Boolean {
        if (className.isNullOrBlank()) return false
        return FRAMEWORK_PREFIXES.none { className.startsWith(it) }
    }

    private fun String.entryName(): String = substringAfterLast("/").ifBlank { this }

    private fun String.decamel(): String {
        val sb = StringBuilder()
        forEachIndexed { i, c ->
            if (c.isUpperCase() && i > 0) sb.append('_')
            sb.append(c.lowercaseChar())
        }
        return sb.toString()
    }

    private fun File.isUnderSourceRoot(): Boolean {
        var p: File? = parentFile
        while (p != null) {
            if (p.name == "src") return true
            p = p.parentFile
        }
        return false
    }

    private companion object {
        val SKIP_DIRS = setOf(
            "build", ".gradle", ".idea", ".git", ".kotlin", "node_modules",
            "bin", "generated", "target", "outputs", "intermediates", ".arts",
        )
        val FRAMEWORK_PREFIXES = listOf(
            "android.", "androidx.", "com.android.", "java.", "kotlin.",
            "com.google.android.", "com.facebook.", "android.widget.",
            "android.view.", "android.webkit.", "android.app.", "android.media.",
            "android.text.", "android.graphics.", "android.os.",
        )
    }
}
