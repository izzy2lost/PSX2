#pragma once
#include "GLContextEGL.h"

#include <span>

class GLContextEGLAndroid final : public GLContextEGL
{
public:
    GLContextEGLAndroid(const WindowInfo& wi);
    ~GLContextEGLAndroid() override;

    static std::unique_ptr<GLContext> Create(const WindowInfo& wi, std::span<const Version> versions_to_try,
                                             Error* error);

    std::unique_ptr<GLContext> CreateSharedContext(const WindowInfo& wi, Error* error) override;
    void ResizeSurface(u32 new_surface_width = 0, u32 new_surface_height = 0) override;

protected:
    EGLDisplay GetPlatformDisplay(Error* error) override;
    EGLSurface CreatePlatformSurface(EGLConfig config, void* win, Error* error) override;
};
