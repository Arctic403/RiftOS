package com.riftpp.editor

data class RiftppElf32PreflightReceipt(
    val bytes: Int,
    val entry: Long,
    val programHeaders: Int,
    val sectionHeaders: Int,
    val sectionTableOffset: Long,
    val loadSegments: Int,
    val executableLoads: Int,
    val writableLoads: Int,
    val rxEnd: Long,
    val rwStart: Long
)

object RiftppNativeElfPreflight {
    private const val ELF32_EHDR_BYTES = 52
    private const val ELF32_PHDR_BYTES = 32
    private const val ELF32_SHDR_BYTES = 40
    private const val EM_ARM = 40
    private const val ET_DYN = 3
    private const val PT_LOAD = 1
    private const val PF_X = 1
    private const val PF_W = 2
    private const val MAX_ELF_BYTES =
        16 * 1024 * 1024
    private const val MAX_PROGRAM_HEADERS = 64
    private const val MAX_SECTION_HEADERS = 256

    private data class Load(
        val vaddr: Long,
        val memEnd: Long,
        val executable: Boolean,
        val writable: Boolean
    )

    fun inspect(
        bytes: ByteArray
    ): RiftppElf32PreflightReceipt {
        require(
            bytes.size in
                ELF32_EHDR_BYTES..MAX_ELF_BYTES
        ) {
            "ELF32 size is out of bounds"
        }

        require(
            u8(bytes, 0) == 0x7f &&
                u8(bytes, 1) == 'E'.code &&
                u8(bytes, 2) == 'L'.code &&
                u8(bytes, 3) == 'F'.code
        ) {
            "ELF magic is invalid"
        }
        require(
            u8(bytes, 4) == 1
        ) {
            "ELF is not 32-bit"
        }
        require(
            u8(bytes, 5) == 1
        ) {
            "ELF is not little-endian"
        }
        require(
            u8(bytes, 6) == 1
        ) {
            "ELF identification version is invalid"
        }
        require(
            u16(bytes, 16) == ET_DYN
        ) {
            "ELF type must be ET_DYN"
        }
        require(
            u16(bytes, 18) == EM_ARM
        ) {
            "ELF machine must be ARM"
        }
        require(
            u32(bytes, 20) == 1L
        ) {
            "ELF version is invalid"
        }

        val entry =
            u32(bytes, 24)
        val phoff =
            u32(bytes, 28)
        val shoff =
            u32(bytes, 32)
        val ehsize =
            u16(bytes, 40)
        val phentsize =
            u16(bytes, 42)
        val phnum =
            u16(bytes, 44)
        val shentsize =
            u16(bytes, 46)
        val shnum =
            u16(bytes, 48)
        val shstrndx =
            u16(bytes, 50)

        require(
            ehsize >= ELF32_EHDR_BYTES
        ) {
            "ELF header size is invalid"
        }
        require(
            phnum in 1..MAX_PROGRAM_HEADERS
        ) {
            "ELF program-header count is invalid"
        }
        require(
            phentsize >= ELF32_PHDR_BYTES
        ) {
            "ELF program-header size is invalid"
        }

        requireRange(
            phoff,
            phentsize.toLong() *
                phnum.toLong(),
            bytes.size,
            "program-header table"
        )

        val loads =
            ArrayList<Load>()
        var executableLoads = 0
        var writableLoads = 0

        repeat(phnum) { index ->
            val base =
                checkedOffset(
                    phoff +
                        index.toLong() *
                        phentsize.toLong()
                )

            val type =
                u32(bytes, base)
            if (type == PT_LOAD.toLong()) {
                val fileOffset =
                    u32(bytes, base + 4)
                val vaddr =
                    u32(bytes, base + 8)
                val fileSize =
                    u32(bytes, base + 16)
                val memSize =
                    u32(bytes, base + 20)
                val flags =
                    u32(bytes, base + 24)
                        .toInt()
                val align =
                    u32(bytes, base + 28)

                require(
                    fileSize <= memSize
                ) {
                    "PT_LOAD filesz exceeds memsz"
                }
                requireRange(
                    fileOffset,
                    fileSize,
                    bytes.size,
                    "PT_LOAD file range"
                )
                require(
                    align == 0L ||
                        isPowerOfTwo(
                            align
                        )
                ) {
                    "PT_LOAD alignment is invalid"
                }

                val executable =
                    flags and PF_X != 0
                val writable =
                    flags and PF_W != 0

                require(
                    !(executable && writable)
                ) {
                    "PT_LOAD is writable and executable"
                }

                val memEnd =
                    checkedAdd(
                        vaddr,
                        memSize,
                        "PT_LOAD virtual range"
                    )

                if (executable) {
                    executableLoads += 1
                }
                if (writable) {
                    writableLoads += 1
                }

                loads +=
                    Load(
                        vaddr = vaddr,
                        memEnd = memEnd,
                        executable = executable,
                        writable = writable
                    )
            }
        }

        require(
            loads.isNotEmpty()
        ) {
            "ELF has no PT_LOAD segments"
        }
        require(
            executableLoads > 0
        ) {
            "ELF has no executable PT_LOAD"
        }
        require(
            writableLoads > 0
        ) {
            "ELF has no writable PT_LOAD"
        }

        val ordered =
            loads.sortedBy {
                it.vaddr
            }

        for (
            index in
            1 until ordered.size
        ) {
            require(
                ordered[index - 1]
                    .memEnd <=
                    ordered[index]
                        .vaddr
            ) {
                "PT_LOAD virtual ranges overlap"
            }
        }

        val rxEnd =
            ordered
                .filter {
                    it.executable
                }
                .maxOf {
                    it.memEnd
                }
        val rwStart =
            ordered
                .filter {
                    it.writable
                }
                .minOf {
                    it.vaddr
                }

        require(
            rxEnd <= rwStart
        ) {
            "executable PT_LOAD crosses writable PT_LOAD"
        }

        require(
            ordered.any {
                it.executable &&
                    entry >= it.vaddr &&
                    entry < it.memEnd
            }
        ) {
            "ELF entry is outside executable PT_LOAD"
        }

        if (shnum > 0) {
            require(
                shoff > 0L
            ) {
                "section headers exist but e_shoff is zero"
            }
            require(
                shnum <=
                    MAX_SECTION_HEADERS
            ) {
                "ELF section-header count is invalid"
            }
            require(
                shentsize >=
                    ELF32_SHDR_BYTES
            ) {
                "ELF section-header size is invalid"
            }
            requireRange(
                shoff,
                shentsize.toLong() *
                    shnum.toLong(),
                bytes.size,
                "section-header table"
            )
            require(
                shstrndx == 0 ||
                    shstrndx < shnum
            ) {
                "ELF shstrndx is invalid"
            }
        } else {
            require(
                shoff == 0L
            ) {
                "ELF has e_shoff without section headers"
            }
        }

        return RiftppElf32PreflightReceipt(
            bytes = bytes.size,
            entry = entry,
            programHeaders = phnum,
            sectionHeaders = shnum,
            sectionTableOffset = shoff,
            loadSegments = loads.size,
            executableLoads =
                executableLoads,
            writableLoads =
                writableLoads,
            rxEnd = rxEnd,
            rwStart = rwStart
        )
    }

    private fun u8(
        bytes: ByteArray,
        offset: Int
    ): Int =
        bytes[offset]
            .toInt() and 0xff

    private fun u16(
        bytes: ByteArray,
        offset: Int
    ): Int =
        u8(bytes, offset) or
            (u8(
                bytes,
                offset + 1
            ) shl 8)

    private fun u32(
        bytes: ByteArray,
        offset: Int
    ): Long =
        (u8(bytes, offset).toLong()) or
            (u8(
                bytes,
                offset + 1
            ).toLong() shl 8) or
            (u8(
                bytes,
                offset + 2
            ).toLong() shl 16) or
            (u8(
                bytes,
                offset + 3
            ).toLong() shl 24)

    private fun checkedOffset(
        value: Long
    ): Int {
        require(
            value in
                0..Int.MAX_VALUE.toLong()
        ) {
            "ELF offset exceeds host bounds"
        }
        return value.toInt()
    }

    private fun checkedAdd(
        left: Long,
        right: Long,
        label: String
    ): Long {
        val sum =
            left + right
        require(
            sum >= left
        ) {
            "$label overflow"
        }
        return sum
    }

    private fun requireRange(
        offset: Long,
        size: Long,
        total: Int,
        label: String
    ) {
        require(
            offset >= 0L &&
                size >= 0L
        ) {
            "$label is negative"
        }
        val end =
            checkedAdd(
                offset,
                size,
                label
            )
        require(
            end <=
                total.toLong()
        ) {
            "$label exceeds ELF bytes"
        }
    }

    private fun isPowerOfTwo(
        value: Long
    ): Boolean =
        value > 0L &&
            (
                value and
                    (value - 1L)
            ) == 0L
}
