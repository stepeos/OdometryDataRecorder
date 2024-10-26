# Odometry Data Recorder

Odometry Data Recorder is a simple Android app designed for recording visual and IMU (Inertial Measurement Unit) data, which can be used for odometry algorithms.

## Features

<p align="center">
  <img src="app-preview.png" />
</p>


- [x] Camera Preview
- [x] Record visual data using the device's camera. -> Done, stored in YUV format from camera2 api
- [x] Record IMU data including accelerometer and gyroscope readings. -> done with android interface
- [x] Save IMU recorded data to files. -> done, serialized with capnproto
- [x] Save Image recorded data to files with timestamp, exposure time and iso sensitivity for each frame -> done, serialized with capnproto
- [x] optionally hardlock exposure time and ISO sensitivity (for calibration)

## Getting Started

### Prerequisites

- Android Studio
- Android device with camera and IMU sensors

### Installation

1. Clone the repository:
    ```sh
    git clone https://github.com/stepeos/OdometryDataRecorder.git
    ```
2. Open the project in Android Studio.
3. Build and run the app on your Android device.

## Usage

1. Launch the app on your Android device.
2. Grant the necessary permissions for camera and sensor access.
3. Start recording visual and IMU data by pressing the appropriate buttons in the app.
4. Save the recorded data for later use in SLAM algorithms.

## Contributing

Contributions are *very much* welcome. Please help.

## License

This project is licensed under the Apache License Version 2.0 - see the [LICENSE](LICENSE) file for details.
