// SPDX-FileCopyrightText: 2002-2026 PCSX2 Dev Team
// SPDX-License-Identifier: GPL-3.0+

#include "GS/Renderers/OpenGL/GLContext.h"

#if defined(_WIN32)
#include "GS/Renderers/OpenGL/GLContextWGL.h"
#elif defined(__ANDROID__)
#include "GS/Renderers/OpenGL/GLContextEGLAndroid.h"
#else // Linux
#ifdef X11_API
#include "GS/Renderers/OpenGL/GLContextEGLX11.h"
#endif
#ifdef WAYLAND_API
#include "GS/Renderers/OpenGL/GLContextEGLWayland.h"
#endif
#endif

#include "common/Console.h"
#include "common/Error.h"

#include "glad/gl.h"

GLContext::GLContext(const WindowInfo& wi)
	: m_wi(wi)
{
}

GLContext::~GLContext() = default;

std::unique_ptr<GLContext> GLContext::Create(const WindowInfo& wi, Error* error)
{
	// We need at least GL3.3.
	static constexpr Version vlist[] = {
		{4, 6},
		{4, 5},
		{4, 4},
		{4, 3},
		{4, 2},
		{4, 1},
		{4, 0},
		{3, 3},
	};
	static constexpr Version gles_vlist[] = {
		{3, 2},
		{3, 1},
	};

	std::unique_ptr<GLContext> context;
	Error local_error;
#if defined(_WIN32)
	context = GLContextWGL::Create(wi, vlist, error);
#elif defined(__ANDROID__)
	context = GLContextEGLAndroid::Create(wi, gles_vlist, error);
#else // Linux
#if defined(X11_API)
	if (wi.type == WindowInfo::Type::X11)
		context = GLContextEGLX11::Create(wi, vlist, error);
#endif

#if defined(WAYLAND_API)
	if (wi.type == WindowInfo::Type::Wayland)
		context = GLContextEGLWayland::Create(wi, vlist, error);
#endif
#endif

	if (!context)
		return nullptr;

	// NOTE: Not thread-safe. But this is okay, since we're not going to be creating more than one context at a time.
	static GLContext* context_being_created;
	context_being_created = context.get();

	// load up glad
	const auto load_proc = [](const char* name) {
		return reinterpret_cast<GLADapiproc>(context_being_created->GetProcAddress(name));
	};
	const int glad_version = context->IsGLES() ? gladLoadGLES2(load_proc) : gladLoadGL(load_proc);
	if (!glad_version)
	{
		Error::SetStringView(error, "Failed to load GL functions for GLAD");
		return nullptr;
	}

	context_being_created = nullptr;

	return context;
}
