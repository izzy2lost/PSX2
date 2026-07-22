// SPDX-FileCopyrightText: 2002-2026 PCSX2 Dev Team
// SPDX-License-Identifier: GPL-3.0+

#pragma once

#include "common/Pcsx2Defs.h"

static const u32 BIAS = 2;				// Bus is half of the actual ps2 speed
// EE bus clock: S246 runs at console speed, S256 at 4/3, Super S256 at 3/2
static const u32 PS2CLK_DEFAULT = 294912000;	//hz	/* 294.912 mhz — PS2 console / S246 */
static const u32 PS2CLK_S256    = 393216000;	//hz	/* 393.216 mhz — S256 (294.912 * 4/3) */
static const u32 PS2CLK_SS256   = 442368000;	//hz	/* 442.368 mhz — Super S256 (294.912 * 3/2) */
extern u32 PS2CLK;
extern u32 PSXCLK;	/* 36.864 Mhz (S246), 49.152 (S256), 55.296 (SS256) */


#include "Memory.h"
#include "R5900.h"
#include "Hw.h"
#include "Dmac.h"

#include "SaveState.h"
#include "DebugTools/Debug.h"

#include <string>

extern std::string ShiftJIS_ConvertString( const char* src );
extern std::string ShiftJIS_ConvertString( const char* src, int maxlen );
