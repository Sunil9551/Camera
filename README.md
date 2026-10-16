📷 Camera

<a href="https://github.com">
  <img src="https://shields.io" alt="Download APK" />
</a>

---




«Because not every story belongs in a stretched 16:9 box.»

📜 License: Apache 2.0 | 📱 Platform: Android (Java)

---

📖 About The Project

Modern smartphone cameras have made 16:9, 19.5:9, 20:9 and other wide formats the default for video. On paper, wider looks modern. But wider isn't always better.

Camera app is built for people who understand the aesthetic of 4:3.

The project is designed around the camera sensor's natural 4:3 shape — giving photos and videos a frame that feels more balanced, natural and intentional.

Instead of treating 4:3 as an old-fashioned leftover from the camera sensor, this project treats it as a feature.

🎞️ Why 4:3?

A lot of modern smartphone video workflows encourage users toward wide formats. The problem is that the sensor itself is commonly closer to 4:3.

When a wider frame becomes the default, part of that natural sensor area may be left unused or cropped away depending on the camera implementation.

And somewhere along the way, we've been taught that:

«Wider = better.»

We don't agree.

4:3 gives you more vertical information while keeping a comfortable, balanced composition. It works beautifully for people, objects, everyday moments, documentary-style footage and photography where you want the frame to feel like an actual image rather than a television screen.

This project is for people who look at a 4:3 frame and think:
“That's the composition I wanted.”

---

✨ What Makes This Project Different?

📐 Native 4:3 First

The entire camera experience is built with 4:3 as the primary aspect ratio.

That means the goal isn't to record a normal wide video and crop it later.

The viewfinder and capture pipeline are designed around the 4:3 format supported by the camera hardware.

📸 Photo + 🎥 Video

This isn't just a video recorder anymore.

The app provides both:

- Photo Mode — for native 4:3 photographs.
- Video Mode — for 4:3 video capture.
- A simple Photo/Video switch keeps both modes in one focused camera interface.

The philosophy stays the same in both modes:

«Keep the frame natural. Keep the composition intentional.»

🧠 Camera2 API

The project uses Android's Camera2 API to communicate directly with the camera hardware and discover capabilities provided by the device.

Resolution, frame rate, codec support, flash capability, zoom limits and other camera characteristics are determined from the device rather than being blindly hard-coded for one particular phone.

📱 Different Phones, Different Hardware

Android devices don't all expose the same camera capabilities.

Camera therefore tries to work with what the phone actually provides.

If a particular resolution or configuration isn't supported, the app can fall back to another compatible option rather than assuming every phone has identical camera hardware.

🎯 A Distraction-Free Camera

The interface is intentionally simple.

No giant collection of filters.

No social-media-first design.

No attempt to turn every shot into a preset.

Just the things that matter:

Frame. Camera. Capture.

---

🧩 The 4:3 Philosophy

There is nothing wrong with 16:9.

It's excellent for televisions, traditional video production and many modern displays.

The problem begins when one aspect ratio becomes the answer to everything.

Smartphone cameras often have sensors with a more natural 4:3 geometry, yet many modern camera experiences guide users toward increasingly wide formats.

That can mean giving up part of the sensor's natural image area simply to fit a wider presentation.

Camera takes the opposite approach.

Instead of asking:

«“How wide can we make this?”»

It asks:

«“How much of the camera's natural frame can we preserve?”»

That's the idea behind this project.

4:3 isn't a compromise here.

4:3 is the point.

---

🔧 Key Features

- 📐 Native 4:3-oriented camera experience
- 📸 4:3 Photo Mode
- 🎥 4:3 Video Mode
- 🎛️ Camera2 API integration
- 🔦 Camera flash / torch support where supported by the device
- 🔍 Device-aware zoom limits
- 🎞️ H.264 video support
- ⚙️ Device-aware resolution and frame-rate selection
- 💾 Separate photo and video storage locations
- 🔢 Sequential photo and video filenames
- 🧭 Automatic orientation handling
- 🔊 Photo capture shutter sound
- 📱 Android 7.0+ support

---

📂 Storage

Photos are saved to:

DCIM/Images/

with filenames such as:

Image_0000.jpg
Image_0001.jpg
Image_0002.jpg

Videos are saved to:

DCIM/Video clips/

with filenames such as:

Video_clip_0000.mp4
Video_clip_0001.mp4
Video_clip_0002.mp4

---

🛠️ Built With

- Language: Java
- Platform: Android
- Core Camera API: Camera2 API
- UI: Android XML
- Build System: Gradle
- Minimum Android Version: Android 7.0 / API 24

---

🚀 Getting Started

Prerequisites

- Android Studio
- Android SDK
- Android device or emulator running API 24 or higher

Run Locally

1. Clone the repository.
2. Open the project in Android Studio.
3. Allow Gradle to sync and download dependencies.
4. Connect an Android device with USB debugging enabled.
5. Press Run.

---

🤝 Contributing

Contributions are welcome.

If you have ideas for improving the camera pipeline, supporting additional devices or making the 4:3 experience better, feel free to contribute.

1. Fork the project.
2. Create a feature branch.
3. Make your changes.
4. Test on real camera hardware where possible.
5. Submit a Pull Request.

---

📄 License

Distributed under the Apache License 2.0.

See "LICENSE" for the complete license text.

---

📇 Project

Camera

A small Android camera project built around one simple idea:

«The camera sensor already has a frame.
We don't always need to cut it into a wider box.»

4:3 isn't outdated.
It's an aesthetic.

## License
Copyright 2026 Sunil Kumar.
Licensed under the Apache License, Version 2.0.

