/**
 * @file ARCADE.h
 * @author El_isra
 * @brief common definitions for arcade related stuff, generic constants and such
 *
 */

#pragma once
enum ACMEDIATYPE {
    ACUNK = -1, //Unknown
    ACCD = 0,
    ACDVD,
    ACHDD,
};

#define ACMEDIATYPE_FROM_STRING(s) \
    ((s) == "CD"  ? ACMEDIATYPE::ACCD : \
    (s) == "DVD" ? ACMEDIATYPE::ACDVD : \
    (s) == "HDD" ? ACMEDIATYPE::ACHDD : \
    ACMEDIATYPE::ACUNK)
