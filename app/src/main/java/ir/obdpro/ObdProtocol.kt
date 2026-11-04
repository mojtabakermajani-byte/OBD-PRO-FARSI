package ir.obdpro

import java.util.Locale

data class PidValue(val name: String, val value: Double, val unit: String)

object ObdProtocol {
    fun clean(s: String) = s.uppercase(Locale.US)
        .replace("SEARCHING...", "").replace("SEARCHING..", "")
        .replace("\\s+".toRegex(), "").replace(">", "")

    fun bytesFor(response: String, mode: String, pid: String): List<Int>? {
        val c = clean(response)
        val marker = (mode + pid).uppercase(Locale.US)
        val i = c.indexOf(marker)
        if (i < 0) return null
        val tail = c.substring(i + marker.length)
        if (tail.length < 2 || tail.length % 2 != 0) return null
        return tail.chunked(2).mapNotNull { it.toIntOrNull(16) }
    }

    fun decode(pid: String, b: List<Int>): PidValue? {
        if (b.isEmpty()) return null
        val a = b[0]
        val x = if (b.size > 1) b[1] else 0
        return when(pid.uppercase(Locale.US)) {
            "04" -> PidValue("بار موتور", a * 100.0 / 255.0, "%")
            "05" -> PidValue("دمای آب موتور", a - 40.0, "°C")
            "0B" -> PidValue("فشار منیفولد", a.toDouble(), "kPa")
            "0C" -> PidValue("دور موتور", (a * 256 + x) / 4.0, "RPM")
            "0D" -> PidValue("سرعت خودرو", a.toDouble(), "km/h")
            "0F" -> PidValue("دمای هوای ورودی", a - 40.0, "°C")
            "10" -> PidValue("دبی هوای ورودی", (a * 256 + x) / 100.0, "g/s")
            "11" -> PidValue("موقعیت دریچه گاز", a * 100.0 / 255.0, "%")
            "42" -> PidValue("ولتاژ ECU", (a * 256 + x) / 1000.0, "V")
            "2F" -> PidValue("سطح سوخت", a * 100.0 / 255.0, "%")
            else -> null
        }
    }

    fun dtcs(response: String): List<String> {
        val c = clean(response)
        val i = c.indexOf("43")
        if (i < 0) return emptyList()
        val data = c.substring(i + 2)
        val out = mutableListOf<String>()
        val chars = data.chunked(4).filter { it.length == 4 }
        for (h in chars) {
            val n = h.toIntOrNull(16) ?: continue
            if (n == 0) continue
            val systems = listOf('P','C','B','U')
            val first = (n shr 14) and 0x3
            val second = (n shr 12) and 0x3
            val code = systems[first] + second.toString() + "%03X".format(n and 0xFFF)
            out.add(code)
        }
        return out
    }

    fun supportedPids(response: String): Set<String> {
        val b = bytesFor(response, "41", "00") ?: return emptySet()
        if (b.size < 4) return emptySet()
        val pids = mutableSetOf<String>()
        for (bit in 0 until 32) if ((b[bit/8] and (1 shl (7-bit%8))) != 0)
            pids.add("%02X".format(bit+1))
        return pids
    }
}
