/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2020  huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.tungsten.fclcore.util;

import com.tungsten.fclcore.game.Library;
import com.tungsten.fclcore.game.Version;

import java.util.List;

/**
 * Detects Minecraft versions that use the LWJGL SDL3 bindings.
 */
public final class SDLVersionSupport {
    private SDLVersionSupport() {
    }

    public static boolean usesSDL3(Version version) {
        return getSDL3LWJGLVersion(version) != null;
    }

    public static boolean usesSDL3(List<Library> libraries) {
        return getSDL3LWJGLVersion(libraries) != null;
    }

    public static String getSDL3LWJGLVersion(Version version) {
        return version == null ? null : getSDL3LWJGLVersion(version.getLibraries());
    }

    public static String getSDL3LWJGLVersion(List<Library> libraries) {
        if (libraries == null) {
            return null;
        }

        String sdlVersion = null;
        String coreVersion = null;
        for (Library library : libraries) {
            if (library == null || isLWJGLNative(library)) {
                continue;
            }
            if (library.is("org.lwjgl", "lwjgl-sdl")) {
                sdlVersion = mergeVersion("lwjgl-sdl", sdlVersion, library.getVersion());
            } else if (library.is("org.lwjgl", "lwjgl")) {
                coreVersion = mergeVersion("lwjgl", coreVersion, library.getVersion());
            }
        }

        if (sdlVersion != null && coreVersion != null && !sdlVersion.equals(coreVersion)) {
            throw new IllegalArgumentException("Mismatched LWJGL SDL/core versions: lwjgl-sdl="
                    + sdlVersion + ", lwjgl=" + coreVersion);
        }
        return sdlVersion;
    }

    private static String mergeVersion(String artifact, String currentVersion, String nextVersion) {
        if (currentVersion != null && !currentVersion.equals(nextVersion)) {
            throw new IllegalArgumentException("Multiple " + artifact + " versions in one game: "
                    + currentVersion + " and " + nextVersion);
        }
        return nextVersion;
    }

    public static boolean isLWJGLNative(Library library) {
        if (library == null || !"org.lwjgl".equals(library.getGroupId())) {
            return false;
        }

        String classifier = library.getClassifier();
        return library.isNative() || (classifier != null && classifier.startsWith("natives-"));
    }
}
