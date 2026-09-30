export const WORLD = { width: 1800, height: 1400, homeX: 900, homeY: 700 };

export function createCamera() {
  return { x: WORLD.homeX, y: WORLD.homeY, viewWidth: 1000, viewHeight: 700, following: true };
}

export function constrainCamera(camera) {
  const clamp = (v, size, view) => view >= size ? size / 2 : Math.max(view / 2, Math.min(size - view / 2, v));
  camera.x = clamp(camera.x, WORLD.width, camera.viewWidth);
  camera.y = clamp(camera.y, WORLD.height, camera.viewHeight);
}

export function centerCamera(camera, target) {
  camera.x = target?.x ?? WORLD.homeX;
  camera.y = target?.y ?? WORLD.homeY;
  camera.following = true;
  constrainCamera(camera);
}

export function screenToWorld(camera, rect, clientX, clientY) {
  const scale = camera.viewWidth / rect.width;
  return { x: camera.x + (clientX - rect.left - rect.width / 2) * scale,
           y: camera.y + (clientY - rect.top - rect.height / 2) * scale };
}

export function panCamera(camera, rect, dx, dy) {
  camera.following = false;
  const scale = camera.viewWidth / rect.width;
  camera.x -= dx * scale;
  camera.y -= dy * scale;
  constrainCamera(camera);
}
