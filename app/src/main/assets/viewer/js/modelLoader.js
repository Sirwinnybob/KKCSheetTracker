import * as THREE from 'three';
import { GLTFLoader } from 'three/addons/loaders/GLTFLoader.js';

export function loadModel(url, maxAnisotropy, onProgress) {
    return new Promise((resolve, reject) => {
        const loader = new GLTFLoader();
        loader.load(url, (gltf) => {
            const model = gltf.scene;
            const detectedMaterials = [];
            const materialMap = new Map();

            model.traverse((child) => {
                if (child.isMesh) {
                    const prevMats = Array.isArray(child.material) ? child.material : [child.material];
                    const newMats = prevMats.map(prevMat => {
                        const newMat = new THREE.MeshLambertMaterial({
                            map: prevMat.map,
                            color: prevMat.map ? 0xffffff : prevMat.color,
                            transparent: prevMat.transparent,
                            opacity: prevMat.opacity,
                            side: THREE.DoubleSide,
                            polygonOffset: true,
                            polygonOffsetFactor: 1,
                            polygonOffsetUnits: 1,
                            name: prevMat.name
                        });
                        if (newMat.map) {
                            newMat.map.colorSpace = THREE.SRGBColorSpace;
                            newMat.map.anisotropy = maxAnisotropy;
                            newMat.map.minFilter  = THREE.LinearMipmapLinearFilter;
                            newMat.map.magFilter  = THREE.LinearFilter;
                        }
                        return newMat;
                    });
                    child.material = Array.isArray(child.material) ? newMats : newMats[0];

                    newMats.forEach((mat, i) => {
                        const prevMat = prevMats[i];
                        if (mat.map) {
                            const texSrc = prevMat.map?.source?.uuid || prevMat.map?.uuid || prevMat.uuid;
                            if (!materialMap.has(texSrc)) {
                                materialMap.set(texSrc, {
                                    name: mat.name || child.name || `Material_${materialMap.size}`,
                                    material: mat,
                                    meshes: [],
                                    hasTexture: true,
                                    originalMap: mat.map
                                });
                            }
                            if (!materialMap.get(texSrc).meshes.includes(child)) materialMap.get(texSrc).meshes.push(child);
                        }
                    });
                    child.geometry.computeVertexNormals();
                    child.castShadow = true;
                    child.receiveShadow = true;
                }
            });

            detectedMaterials.push(...Array.from(materialMap.values()));
            resolve({ model, detectedMaterials });
        }, onProgress, reject);
    });
}
