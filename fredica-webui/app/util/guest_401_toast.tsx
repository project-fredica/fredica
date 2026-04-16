import { toast } from "react-toastify";
import { isBridgeAvailable } from "~/util/bridge";

let _shown = false;

export function showGuest401Toast() {
    // 桌面端用户即服务主人，不需要游客登录提示
    if (isBridgeAvailable()) return;
    if (sessionStorage.getItem("fredica_guest_401_dismissed")) return;
    if (_shown) return;
    _shown = true;

    toast(
        ({ closeToast }) => (
            <div>
                <p className="text-sm mb-2">
                    你现在是游客，一般只能访问只读接口，是否有账户需要登录？
                </p>
                <div className="flex gap-2">
                    <button
                        className="text-xs px-2 py-1 rounded bg-slate-700 text-white hover:bg-slate-600 cursor-pointer"
                        onClick={() => {
                            closeToast?.();
                            window.location.href = "/login";
                        }}
                    >
                        去登录
                    </button>
                    <button
                        className="text-xs px-2 py-1 rounded border border-slate-300 text-slate-600 hover:bg-slate-50 cursor-pointer"
                        onClick={() => {
                            sessionStorage.setItem("fredica_guest_401_dismissed", "1");
                            closeToast?.();
                        }}
                    >
                        不，且本次访问不再弹出
                    </button>
                </div>
            </div>
        ),
        {
            autoClose: false,
            closeOnClick: false,
            position: "top-center",
            onClose: () => { _shown = false; },
        },
    );
}
