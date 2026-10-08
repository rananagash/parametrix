"use client";
import { Canvas } from "@react-three/fiber";
import { Bounds, OrbitControls, Grid } from "@react-three/drei";
import { STLLoader } from "three/examples/jsm/loaders/STLLoader.js";
import { useEffect, useState } from "react";
import type { BufferGeometry } from "three";

export default function Viewer({ url }: { url: string | null }) {
  const [geometry, setGeometry] = useState<BufferGeometry | null>(null);
  const [error, setError] = useState("");
  useEffect(() => {
    if (!url) return;
    const controller = new AbortController();
    setError("");
    fetch(url, { signal: controller.signal })
      .then((response) => {
        if (!response.ok) throw new Error("Mesh unavailable or expired.");
        return response.arrayBuffer();
      })
      .then((buffer) => {
        if (controller.signal.aborted) return;
        const loaded = new STLLoader().parse(buffer);
        loaded.computeVertexNormals();
        loaded.center();
        setGeometry(loaded);
      })
      .catch((error) => {
        if (!controller.signal.aborted) setError(error.message);
      });
    return () => {
      controller.abort();
    };
  }, [url]);
  useEffect(
    () => () => {
      geometry?.dispose();
    },
    [geometry],
  );
  return (
    <div className="relative h-full min-h-[420px]" aria-label="3D model viewer">
      <Canvas camera={{ position: [70, 65, 85], fov: 45 }}>
        <color attach="background" args={["#12161c"]} />
        <ambientLight intensity={0.7} />
        <directionalLight position={[40, 80, 60]} intensity={2.5} />
        <directionalLight position={[-40, 20, -30]} intensity={1.2} />
        <Grid
          position={[
            0,
            geometry ? (geometry.boundingBox?.min.z ?? 0) - 0.1 : -0.01,
            0,
          ]}
          args={[200, 200]}
          cellSize={10}
          sectionSize={50}
          cellColor="#28313a"
          sectionColor="#39434d"
          fadeDistance={250}
          infiniteGrid
        />
        {geometry && (
          <Bounds key={geometry.uuid} fit clip observe margin={1.5}>
            <mesh geometry={geometry} rotation={[-Math.PI / 2, 0, 0]}>
              <meshStandardMaterial
                color="#b4ee49"
                roughness={0.4}
                metalness={0.12}
              />
            </mesh>
          </Bounds>
        )}
        <OrbitControls makeDefault />
      </Canvas>
      {!geometry && (
        <div className="pointer-events-none absolute inset-0 flex flex-col items-center justify-center text-center">
          <div className="mb-5 text-6xl text-[#b4ee49]">◇</div>
          <p className="text-lg text-gray-300">
            Your next idea takes shape here
          </p>
          <p className="mt-2 text-sm text-gray-500">
            Describe a part to generate your first model
          </p>
        </div>
      )}
      {error && (
        <p
          role="alert"
          className="absolute bottom-14 left-5 text-sm text-red-300"
        >
          {error}
        </p>
      )}
      <div className="pointer-events-none absolute bottom-4 left-5 text-xs text-gray-500">
        DRAG TO ORBIT · SCROLL TO ZOOM · RIGHT DRAG TO PAN
      </div>
    </div>
  );
}
