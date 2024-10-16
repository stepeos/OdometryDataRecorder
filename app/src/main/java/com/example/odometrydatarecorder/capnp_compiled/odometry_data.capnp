@0x9eb32e19f86ee174;
using Java = import "java.capnp";
$Java.package("com.example.odometrydatarecorder.capnp_compiled");
$Java.outerClassname("OdometryData");

struct Accelerometer{
  xAcc @0 :Float32;
  yAcc @1 :Float32;
  zAcc @2 :Float32;
}

struct Gyroscope{
  rollAng @0 :Float32;
  pitchAng @1 :Float32;
  yawAng @2 :Float32;
}

struct IMUEntry {
    timestamp @0 :Int64;
    union {
        entryAcc @1: Accelerometer;
        entryGyro @2: Gyroscope;
    }
}

struct IMUData{
    entries @0 :List(IMUEntry);
}

struct CameraCapture {
    timestamp @0 :Int64;
    capture @1 :Data    ;
}

struct CameraData {
    entries @0 :List(CameraCapture);
}


