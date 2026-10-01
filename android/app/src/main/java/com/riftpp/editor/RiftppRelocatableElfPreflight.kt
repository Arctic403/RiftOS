package com.riftpp.editor

data class RiftppElf32RelocatablePreflightReceipt(
    val bytes: Int,
    val sectionHeaders: Int,
    val sectionTableOffset: Long,
    val executableSections: Int,
    val symbolCount: Int,
    val requiredSymbol: String
)

object RiftppRelocatableElfPreflight {
    private const val ELF32_EHDR_BYTES = 52
    private const val ELF32_SHDR_BYTES = 40
    private const val ELF32_SYM_BYTES = 16
    private const val EM_ARM = 40
    private const val ET_REL = 1
    private const val SHT_PROGBITS = 1L
    private const val SHT_SYMTAB = 2L
    private const val SHT_STRTAB = 3L
    private const val SHT_NOBITS = 8L
    private const val SHF_EXECINSTR = 0x4L
    private const val STB_GLOBAL = 1
    private const val STT_FUNC = 2
    private const val SHN_UNDEF = 0
    private const val MAX_ELF_BYTES = 16 * 1024 * 1024
    private const val MAX_SECTION_HEADERS = 256

    private data class Section(
        val type: Long,
        val flags: Long,
        val offset: Long,
        val size: Long,
        val link: Int,
        val info: Int,
        val align: Long,
        val entrySize: Long
    )

    fun inspect(
        bytes: ByteArray,
        requiredGlobalFunction: String = "android_main"
    ): RiftppElf32RelocatablePreflightReceipt {
        require(bytes.size in ELF32_EHDR_BYTES..MAX_ELF_BYTES) {
            "ELF32 relocatable size is out of bounds"
        }
        require(
            u8(bytes, 0) == 0x7f &&
                u8(bytes, 1) == 'E'.code &&
                u8(bytes, 2) == 'L'.code &&
                u8(bytes, 3) == 'F'.code
        ) { "ELF magic is invalid" }
        require(u8(bytes, 4) == 1) { "ELF is not 32-bit" }
        require(u8(bytes, 5) == 1) { "ELF is not little-endian" }
        require(u8(bytes, 6) == 1) { "ELF identification version is invalid" }
        require(u16(bytes, 16) == ET_REL) { "ELF type must be ET_REL" }
        require(u16(bytes, 18) == EM_ARM) { "ELF machine must be ARM" }
        require(u32(bytes, 20) == 1L) { "ELF version is invalid" }
        require(u32(bytes, 24) == 0L) { "ET_REL must not define an entry address" }
        require(u32(bytes, 28) == 0L) { "ET_REL must not define a program-header table" }

        val shoff = u32(bytes, 32)
        val ehsize = u16(bytes, 40)
        val phnum = u16(bytes, 44)
        val shentsize = u16(bytes, 46)
        val shnum = u16(bytes, 48)
        val shstrndx = u16(bytes, 50)

        require(ehsize >= ELF32_EHDR_BYTES) { "ELF header size is invalid" }
        require(phnum == 0) { "ET_REL must not contain program headers" }
        require(shoff > 0L) { "ET_REL section table is missing" }
        require(shnum in 1..MAX_SECTION_HEADERS) { "ELF section-header count is invalid" }
        require(shentsize >= ELF32_SHDR_BYTES) { "ELF section-header size is invalid" }
        require(shstrndx in 1 until shnum) { "ELF shstrndx is invalid" }

        requireRange(
            shoff,
            shentsize.toLong() * shnum.toLong(),
            bytes.size,
            "section-header table"
        )

        val sections = ArrayList<Section>(shnum)
        repeat(shnum) { index ->
            val base = checkedOffset(shoff + index.toLong() * shentsize.toLong())
            val type = u32(bytes, base + 4)
            val flags = u32(bytes, base + 8)
            val offset = u32(bytes, base + 16)
            val size = u32(bytes, base + 20)
            val link = u32(bytes, base + 24).toInt()
            val info = u32(bytes, base + 28).toInt()
            val align = u32(bytes, base + 32)
            val entrySize = u32(bytes, base + 36)

            require(align == 0L || isPowerOfTwo(align)) {
                "ELF section alignment is invalid"
            }
            if (type != SHT_NOBITS && size > 0L) {
                requireRange(offset, size, bytes.size, "section file range")
            }
            sections += Section(type, flags, offset, size, link, info, align, entrySize)
        }

        val executableSections = sections.count {
            it.type == SHT_PROGBITS &&
                it.flags and SHF_EXECINSTR != 0L &&
                it.size > 0L
        }
        require(executableSections > 0) { "ET_REL has no executable PROGBITS section" }

        val symtabIndex = sections.indexOfFirst { it.type == SHT_SYMTAB }
        require(symtabIndex > 0) { "ET_REL has no symbol table" }
        val symtab = sections[symtabIndex]
        require(symtab.link in sections.indices) { "symbol table string-table link is invalid" }
        val strtab = sections[symtab.link]
        require(strtab.type == SHT_STRTAB) { "symbol table does not link to STRTAB" }
        require(symtab.entrySize >= ELF32_SYM_BYTES) { "symbol-table entry size is invalid" }
        require(symtab.size % symtab.entrySize == 0L) { "symbol-table size is not entry-aligned" }

        val symbolCount = (symtab.size / symtab.entrySize).toInt()
        require(symbolCount > 1) { "ET_REL symbol table is empty" }
        require(symtab.info in 1..symbolCount) { "symbol-table local/global boundary is invalid" }

        var found = false
        repeat(symbolCount) { index ->
            val base = checkedOffset(symtab.offset + index.toLong() * symtab.entrySize)
            val nameOffset = u32(bytes, base).toInt()
            val value = u32(bytes, base + 4)
            val size = u32(bytes, base + 8)
            val symbolInfo = u8(bytes, base + 12)
            val sectionIndex = u16(bytes, base + 14)
            val bind = symbolInfo ushr 4
            val type = symbolInfo and 0x0f

            if (
                bind == STB_GLOBAL &&
                type == STT_FUNC &&
                stringAt(bytes, strtab, nameOffset) == requiredGlobalFunction
            ) {
                require(sectionIndex != SHN_UNDEF && sectionIndex in sections.indices) {
                    "required function is undefined"
                }
                val target = sections[sectionIndex]
                require(target.flags and SHF_EXECINSTR != 0L) {
                    "required function is not in an executable section"
                }
                require(value + size <= target.size) {
                    "required function exceeds its section"
                }
                found = true
            }
        }

        require(found) {
            "required global function is missing: $requiredGlobalFunction"
        }

        return RiftppElf32RelocatablePreflightReceipt(
            bytes = bytes.size,
            sectionHeaders = shnum,
            sectionTableOffset = shoff,
            executableSections = executableSections,
            symbolCount = symbolCount,
            requiredSymbol = requiredGlobalFunction
        )
    }

    private fun stringAt(
        bytes: ByteArray,
        section: Section,
        offset: Int
    ): String {
        require(offset >= 0 && offset.toLong() < section.size) {
            "symbol name offset is outside STRTAB"
        }
        val start = checkedOffset(section.offset + offset.toLong())
        val limit = checkedOffset(section.offset + section.size)
        val out = StringBuilder()
        var cursor = start
        while (cursor < limit && bytes[cursor].toInt() != 0) {
            out.append((bytes[cursor].toInt() and 0xff).toChar())
            cursor += 1
        }
        require(cursor < limit) { "unterminated STRTAB string" }
        return out.toString()
    }

    private fun u8(bytes: ByteArray, offset: Int): Int =
        bytes[offset].toInt() and 0xff

    private fun u16(bytes: ByteArray, offset: Int): Int =
        u8(bytes, offset) or (u8(bytes, offset + 1) shl 8)

    private fun u32(bytes: ByteArray, offset: Int): Long =
        u8(bytes, offset).toLong() or
            (u8(bytes, offset + 1).toLong() shl 8) or
            (u8(bytes, offset + 2).toLong() shl 16) or
            (u8(bytes, offset + 3).toLong() shl 24)

    private fun checkedOffset(value: Long): Int {
        require(value in 0..Int.MAX_VALUE.toLong()) { "ELF offset overflow" }
        return value.toInt()
    }

    private fun requireRange(
        offset: Long,
        size: Long,
        fileSize: Int,
        label: String
    ) {
        require(offset >= 0L && size >= 0L) { "$label is negative" }
        val end = offset + size
        require(end >= offset && end <= fileSize.toLong()) {
            "$label is outside the ELF"
        }
    }

    private fun isPowerOfTwo(value: Long): Boolean =
        value > 0L && value and (value - 1L) == 0L
}
