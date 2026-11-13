package main

/*
#include <jni.h>
#include <stdlib.h>
#include <stdint.h>

static const char* v2_get_string(JNIEnv* env, jstring value) {
    return (*env)->GetStringUTFChars(env, value, 0);
}
static void v2_release_string(JNIEnv* env, jstring value, const char* chars) {
    (*env)->ReleaseStringUTFChars(env, value, chars);
}
static void* v2_direct_buffer(JNIEnv* env, jobject buffer) {
    return (*env)->GetDirectBufferAddress(env, buffer);
}
static jstring v2_new_string(JNIEnv* env, const char* value) {
    return (*env)->NewStringUTF(env, value);
}
*/
import "C"

import (
	"context"
	"crypto/tls"
	"errors"
	"net"
	"os"
	"sync"
	"unsafe"

	engine "io.surprise.vpn/payloadengine"
)

type state struct {
	mu      sync.Mutex
	next    int64
	conns   map[int64]net.Conn
	lastErr string
}

var runtime = &state{next: 1, conns: make(map[int64]net.Conn)}

func cString(env *C.JNIEnv, value C.jstring) string {
	if value == 0 {
		return ""
	}
	chars := C.v2_get_string(env, value)
	if chars == nil {
		return ""
	}
	result := C.GoString(chars)
	C.v2_release_string(env, value, chars)
	return result
}

func setError(err error) { runtime.mu.Lock(); defer runtime.mu.Unlock(); if err == nil { runtime.lastErr = "" } else { runtime.lastErr = err.Error() } }

//export Java_io_surprise_vpn_core_PayloadNative_open
func Java_io_surprise_vpn_core_PayloadNative_open(env *C.JNIEnv, clazz C.jclass, proxyHost C.jstring, proxyPort C.jint, targetHost C.jstring, targetPort C.jint, sni C.jstring, tls C.jboolean, payload C.jstring) C.jlong {
	cfg := engine.Config{ProxyHost: cString(env, proxyHost), ProxyPort: int(proxyPort), TargetHost: cString(env, targetHost), TargetPort: int(targetPort), SNI: cString(env, sni), TLS: tls != 0, Payload: cString(env, payload)}
	conn, _, err := engine.Open(context.Background(), cfg)
	if err != nil { setError(err); return 0 }
	runtime.mu.Lock(); id := runtime.next; runtime.next++; runtime.conns[id] = conn; runtime.lastErr = ""; runtime.mu.Unlock()
	return C.jlong(id)
}

//export Java_io_surprise_vpn_core_PayloadNative_openOnFd
func Java_io_surprise_vpn_core_PayloadNative_openOnFd(env *C.JNIEnv, clazz C.jclass, fd C.jint, targetHost C.jstring, targetPort C.jint, sni C.jstring, tlsFlag C.jboolean, payload C.jstring) C.jlong {
	file := os.NewFile(uintptr(fd), "v2tun-payload")
	if file == nil { setError(errors.New("invalid protected payload descriptor")); return 0 }
	conn, err := net.FileConn(file)
	if err != nil { _ = file.Close(); setError(err); return 0 }
	if tlsFlag != 0 {
		serverName := cString(env, sni)
		if serverName == "" { serverName = cString(env, targetHost) }
		secure := tls.Client(conn, &tls.Config{ServerName: serverName, MinVersion: tls.VersionTLS12})
		if err := secure.HandshakeContext(context.Background()); err != nil { _ = conn.Close(); setError(err); return 0 }
		conn = secure
	}
	opened, _, err := engine.OpenOnConn(context.Background(), engine.Config{TargetHost: cString(env, targetHost), TargetPort: int(targetPort), SNI: cString(env, sni), TLS: tlsFlag != 0, Payload: cString(env, payload)}, conn)
	if err != nil { _ = conn.Close(); setError(err); return 0 }
	runtime.mu.Lock(); id := runtime.next; runtime.next++; runtime.conns[id] = opened; runtime.lastErr = ""; runtime.mu.Unlock()
	return C.jlong(id)
}

//export Java_io_surprise_vpn_core_PayloadNative_read
func Java_io_surprise_vpn_core_PayloadNative_read(env *C.JNIEnv, clazz C.jclass, handle C.jlong, buffer C.jobject, max C.jint) C.jint {
	if max <= 0 || buffer == 0 { return 0 }
	address := C.v2_direct_buffer(env, buffer); if address == nil { setError(errors.New("read buffer must be direct")); return -1 }
	runtime.mu.Lock(); conn := runtime.conns[int64(handle)]; runtime.mu.Unlock(); if conn == nil { return -1 }
	data := unsafe.Slice((*byte)(address), int(max)); n, err := conn.Read(data); if err != nil && n == 0 { return 0 }; return C.jint(n)
}

//export Java_io_surprise_vpn_core_PayloadNative_write
func Java_io_surprise_vpn_core_PayloadNative_write(env *C.JNIEnv, clazz C.jclass, handle C.jlong, buffer C.jobject, length C.jint) C.jint {
	if length <= 0 || buffer == 0 { return 0 }
	address := C.v2_direct_buffer(env, buffer); if address == nil { setError(errors.New("write buffer must be direct")); return -1 }
	runtime.mu.Lock(); conn := runtime.conns[int64(handle)]; runtime.mu.Unlock(); if conn == nil { return -1 }
	data := unsafe.Slice((*byte)(address), int(length)); n, err := conn.Write(data); if err != nil { setError(err); return -1 }; return C.jint(n)
}

//export Java_io_surprise_vpn_core_PayloadNative_close
func Java_io_surprise_vpn_core_PayloadNative_close(env *C.JNIEnv, clazz C.jclass, handle C.jlong) {
	runtime.mu.Lock(); conn := runtime.conns[int64(handle)]; delete(runtime.conns, int64(handle)); runtime.mu.Unlock(); if conn != nil { _ = conn.Close() }
}

//export Java_io_surprise_vpn_core_PayloadNative_lastError
func Java_io_surprise_vpn_core_PayloadNative_lastError(env *C.JNIEnv, clazz C.jclass) C.jstring {
	runtime.mu.Lock(); message := runtime.lastErr; runtime.mu.Unlock()
	return C.v2_new_string(env, C.CString(message))
}

func main() {}
