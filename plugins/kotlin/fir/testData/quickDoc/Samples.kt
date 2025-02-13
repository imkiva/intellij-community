package magic

object Samples {
    fun sampleMagic() {
        castTextSpell("[asd] [dse] [asz]")
    }
}

fun sampleScroll() {
    val reader = Scroll("[asd] [dse] [asz]").reader()
    castTextSpell(reader.readAll())
}

/**
 * @sample Samples.sampleMagic
 * @sample sampleScroll
 */
fun <caret>castTextSpell(spell: String) {
    throw SecurityException("Magic prohibited outside Hogwarts")
}

//INFO: <div class='definition'><pre>fun castTextSpell(spell: String): Unit</pre></div><table class='sections'><tr><td valign='top' class='section'><p>Samples:</td><td valign='top'><p><a href="psi_element://Samples.sampleMagic"><code style='font-size:96%;'><span style="color:#000000;">Samples</span><span style="">.</span><span style="color:#4585be;">sampleMagic</span></code></a><pre><code><span style=""><span style="color:#808080;font-style:italic;">//&#32;Unresolved</span></span></code></pre><p><a href="psi_element://sampleScroll"><code style='font-size:96%;'><span style="color:#4585be;">sampleScroll</span></code></a><pre><code><span style=""><span style="color:#808080;font-style:italic;">//&#32;Unresolved</span></span></code></pre></td></table><div class='bottom'><icon src="class"/>&nbsp;<a href="psi_element://magic"><code><span style="color:#000000;">magic</span></code></a><br/><icon src="file"/>&nbsp;Samples.kt<br/></div>
