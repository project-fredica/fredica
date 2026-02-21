
import React from 'react';
import { Plus, ChevronLeft, ChevronRight, ChevronsLeft, ChevronsRight, ChevronDown } from 'lucide-react';

interface FooterProps {
    currentPage: number;
    totalPages: number;
    pageSize: number;
    totalRecords: number;
    onPageChange: (page: number) => void;
    onPageSizeChange: (size: number) => void;
}

export const Footer: React.FC<FooterProps> = ({
    currentPage,
    totalPages,
    pageSize,
    totalRecords,
    onPageChange,
    onPageSizeChange
}) => {
    const startRecord = (currentPage - 1) * pageSize + 1;
    const endRecord = Math.min(currentPage * pageSize, totalRecords);

    return (
        <footer className="h-10 border-t border-gray-200 bg-white flex items-center px-4 justify-between text-xs text-gray-500 shrink-0">
            <div className="flex items-center gap-2">
                <button className="flex items-center gap-1.5 text-gray-700 hover:text-blue-600 font-medium px-2 py-1 rounded hover:bg-blue-50">
                    <Plus size={16} />
                    New Record
                </button>
                <button className="p-1 hover:bg-gray-100 rounded">
                    <ChevronDown size={14} />
                </button>
            </div>

            <div className="flex items-center gap-6">
                <div className="flex items-center gap-2">
                    <button
                        onClick={() => onPageChange(1)}
                        disabled={currentPage === 1}
                        className={`p-1 ${currentPage === 1 ? 'text-gray-300 cursor-not-allowed' : 'text-gray-500 hover:text-gray-800'}`}
                    >
                        <ChevronsLeft size={16} />
                    </button>
                    <button
                        onClick={() => onPageChange(currentPage - 1)}
                        disabled={currentPage === 1}
                        className={`p-1 ${currentPage === 1 ? 'text-gray-300 cursor-not-allowed' : 'text-gray-500 hover:text-gray-800'}`}
                    >
                        <ChevronLeft size={16} />
                    </button>
                    <div className="flex items-center gap-1 mx-2">
                        <span className="px-2 py-0.5 border border-gray-200 rounded text-blue-600 bg-blue-50 font-bold min-w-[24px] text-center">
                            {currentPage}
                        </span>
                        <span className="whitespace-nowrap">of {totalPages}</span>
                    </div>
                    <button
                        onClick={() => onPageChange(currentPage + 1)}
                        disabled={currentPage === totalPages}
                        className={`p-1 ${currentPage === totalPages ? 'text-gray-300 cursor-not-allowed' : 'text-gray-500 hover:text-gray-800'}`}
                    >
                        <ChevronRight size={16} />
                    </button>
                    <button
                        onClick={() => onPageChange(totalPages)}
                        disabled={currentPage === totalPages}
                        className={`p-1 ${currentPage === totalPages ? 'text-gray-300 cursor-not-allowed' : 'text-gray-500 hover:text-gray-800'}`}
                    >
                        <ChevronsRight size={16} />
                    </button>
                </div>

                <div className="flex items-center gap-2">
                    <span>Records per page</span>
                    <select
                        value={pageSize}
                        onChange={(e) => {
                            onPageSizeChange(Number(e.target.value));
                            onPageChange(1);
                        }}
                        className="flex items-center gap-1 px-2 py-0.5 border border-gray-200 rounded hover:border-gray-300 bg-white outline-none cursor-pointer"
                    >
                        {[10, 20, 50, 100].map(size => (
                            <option key={size} value={size}>{size}</option>
                        ))}
                    </select>
                </div>

                <div className="text-gray-400 font-medium">
                    {totalRecords > 0 ? `${startRecord} - ${endRecord}` : '0'} of {totalRecords} Records
                </div>
            </div>
        </footer>
    );
};